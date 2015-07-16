(ns pgqueue
  (:refer-clojure :exclude [take count])
  (:require [clojure.string :as string]
            [clojure.java.jdbc :as jdbc]
            [pgqueue.serializer.protocol :as s]
            [pgqueue.serializer.nippy :as nippy-serializer]))

(defrecord PGQueue [name config])
(defrecord PGQueueItem [queue id name priority data deleted])
(defrecord PGQueueLock [queue lock-id-1 lock-id-2])
(defrecord PGQueueLockedItem [item lock])

;; Track the current locks held by a JVM such that
;; worker threads sharing a queue can take multiple
;; items across a postgresql session.  Postgresql's
;; advisory locks handle locking for separate processes.
;; We also want to execute unlocks on the same connection
;; a lock was made (if that connection is still open), so
;; we store the db connection used for each lock

;; Each *qlocks* entry looks like:
;; {'qname' [{:lock-id 123 :db-id <db pool id>},...]}
(def ^:private ^:dynamic *qlocks* (atom {}))

(defn- get-qlocks
  [qname]
  (get @*qlocks* qname))

(defn- get-qlocks-ids
  [qname]
  (map :lock-id (get-qlocks qname)))

(def ^:private db-pool-size (* 2 (.. Runtime getRuntime availableProcessors)))
(def ^:private ^:dynamic *db-pool* (atom (into [] (repeat db-pool-size nil))))

(defn- new-db-pool-conn
  [db-spec db-pool-id]
  (let [conn (merge db-spec
               {:connection (jdbc/get-connection db-spec)})]
    (swap! *db-pool* assoc db-pool-id conn)
    conn))

(defn- get-db-and-id
  "Get random db connection and its db-pool-id from pool.
   Returns [db-conn db-pool-id] vector."
  ([db-spec] (get-db-and-id db-spec (rand-int db-pool-size)))
  ([db-spec db-pool-id]
   (let [pdb (or (get @*db-pool* db-pool-id)
               (new-db-pool-conn db-spec db-pool-id))]
     (try
       (jdbc/query pdb ["select 1"])
       (catch java.sql.SQLException e
         [(new-db-pool-conn db-spec db-pool-id) db-pool-id]))
     [pdb db-pool-id])))

(defn- get-db
  "Get random db connection from pool"
  ([db-spec] (first (get-db-and-id db-spec (rand-int db-pool-size))))
  ([db-spec db-pool-id] (first (get-db-and-id db-spec db-pool-id))))

(def ^:private default-config
  {:db {:classname "org.postgresql.Driver"}
   :schema "public"
   :table "pgqueues"
   :delete true
   :default-priority 100
   :serializer (nippy-serializer/nippy-serializer)})

(defn- merge-with-default-config
  [config]
  (merge (assoc default-config
           :db (:db default-config)) config))

(defn- qt
  "Quote name for pg"
  [name]
  (jdbc/quoted \" name))

(defn- sql-not-in
  "Create an sql \"not in\" clause based on the count of things.
   Returns nil for count = 0"
  ([field things]
   (when (> (clojure.core/count things) 0)
     (str " " field " not in ("
       (string/join "," (repeat (clojure.core/count things) "?")) ") "))))

(defn- sql-values
  "Prep sql and values for jdbc/query,
   flattening to accommodate sql in clause "
  [sql & values]
  (apply vector (remove nil? (flatten [sql values]))))


(defn- schema-exists?
  [db schema]
  (let [rs (jdbc/query db
             ["select 1 
               from pg_namespace 
               where nspname = ?" schema])]
    (> (clojure.core/count rs) 0)))

(defn- table-exists?
  [db schema table]
  (let [rs (jdbc/query db
             ["select 1 
               from   pg_catalog.pg_class c
               join   pg_catalog.pg_namespace n 
                      on n.oid = c.relnamespace
               where  n.nspname = ?
                 and  c.relname = ?" schema table])]
    (> (clojure.core/count rs) 0)))

(defn- table-oid
  [db schema table]
  (let [rs (jdbc/query db
             ["SELECT c.oid as table_oid
               FROM   pg_catalog.pg_class c
               JOIN   pg_catalog.pg_namespace n 
                      ON n.oid = c.relnamespace
               WHERE  n.nspname = ?
                 AND  c.relname = ?" schema table])]
    (:table_oid (first rs))))

(defn- qt-table
  "fully qualified and quoted schema.table"
  [schema table]
  (str (qt schema) "." (qt table)))

(defn- validate-config
  [{:keys [db schema default-priority serializer]}]
  (when (empty? db)
    (throw (ex-info "config requires a :db key containing a clojure.java.jdbc db-spec" {})))
  (when (not (schema-exists? (get-db db) schema))
    (throw (ex-info (str ":schema \"" schema "\" does not exist") {})))
  (when (not (integer? default-priority))
    (throw (ex-info ":default-priority must be an integer" {})))
  (when (not (satisfies? s/Serializer serializer))
    (throw (ex-info ":serializer must satisfy pgqueue.serializer.protocol.Serializer protocol"))))

(defn- create-queue-table!
  "Create the queue table if it does not exist"
  [q]
  (let [{:keys [db schema table default-priority]} (:config q)]
    (when (not (table-exists? (get-db db) schema table))
      (jdbc/execute! (get-db db)
        [(str "create table " (qt-table schema table) " (\n"
           " id bigserial,\n"
           " name text not null,\n"
           " priority integer not null default " default-priority ", \n"
           " data bytea,\n"
           " deleted boolean not null default false,\n"
           " constraint " (qt (str table "_pkey")) "\n"
           "  primary key (name, priority, id, deleted));")]))))

(defn- delete-queue!
  "Delete the rows for the given queue"
  [q]
  (let [{:keys [db schema table]} (:config q)
        qname (name (:name q))]
    (jdbc/with-db-transaction [tx (get-db db)]
      (> (first (jdbc/delete! tx (qt-table schema table)
                  ["name = ?", qname])) 0))))

(defn- drop-queue-table!
  "Drop the queue table for the given queue's config"
  [{:keys [db schema table]}]
  (jdbc/execute! (get-db db)
    [(str "drop table if exists " (qt-table schema table))]))

(defn- unlock-queue-locks!
  "Unlock all advisory locks for the queue"
  [q]
  (let [{:keys [db schema table]} (:config q)
        db    (get-db db)
        qname (name (:name q))
        table-oid (table-oid db schema table)
        locks (jdbc/query db
                ["select classid, objid 
                  from pg_locks where classid = ?" table-oid])]
    (swap! *qlocks* assoc qname [])
    (doseq [lock locks]
      (jdbc/query db
        [(str "select pg_advisory_unlock(cast(? as int),cast(q.id as int)) \n"
           "from " (qt-table schema table) " as q\n"
           "where name = ?")
         table-oid
         qname]))))

(defn- unlock-queue-table-locks!
  "Unlock all advisory locks for all queues in queue table"
  [{:keys [db schema table]}]
  (let [db (get-db db)
        table-oid (table-oid db schema table)
        locks (jdbc/query db
                ["select classid, objid 
                  from pg_locks where classid = ?" table-oid])]
    (swap! *qlocks* {})
    (doseq [lock locks]
      (jdbc/query db
        ["select pg_advisory_unlock(?,?)"
         (:classid lock)
         (:objid lock)]))))

(defn destroy-queue!
  "Unlocks any existing advisory locks for rows of this queue's
   table and deletes all rows for the queue from the queue table."
  [q]
  (delete-queue! q)
  (unlock-queue-locks! q))

(defn destroy-all-queues!
  "Drop the queue table, then unlock any existing advisory locks.
   This function takes the same config hashmap used in pgqueue/queue."
  [config]
  (let [config (merge-with-default-config config)]
    (validate-config config)
    (drop-queue-table! config)
    (unlock-queue-table-locks! config)))

(defn queue
  "Specify a queue with a name and a config.  
   Creates the underlying queue table if it
   does not yet exist.

   - name can be a keyword or string   
   - config is a hashmap with the following keys:
       :db - clojure.java.jdbc db-spec

     and optional keys:
       :schema - schema name (default is \"public\"
       :table  - table name (default is \"pgqueues\")
       :delete - delete behavior upon successful take:
                 - true (default) deletes queue item row
                 - false sets a deleted_flag to true
                   (persists all queue items; see pgqueue/purge-deleted)
       :default-priority - default priority for puts not specifying
                           a priority; must be an integer
                           where a lower value = higher priority; negative integers 
                           are also accepted
       :serializer - instance of a type that implements
                     pgqueue.serializer.Serializer protocol
                       default is instance of pgqueue.serializer.nippy/NippySerializer
                       (pgqueue.serializer.fressian/FressianSerializer is available, too)"
  [name config]
  (let [config (merge-with-default-config config)]
    (validate-config config)
    (let [q (->PGQueue name config)]
      (create-queue-table! q)
      q)))

(defn put
  "Put item onto queue.
   
   usage: (put q item)
          (put q priority item)

   Returns true on success, false on failure, nil on no-op.

   item can be any serializable Clojure data.

   When item is nil, put is a no-op and returns nil.
   
   For arity of 2, a default priority is used.
   For arity of 3, the second argument is a priority integer
   where a lower value = higher priority; negative integers 
   are also accepted.

   Examples:
   (pgq/put q -10 \"urgent\")
   (pgq/put q 1   \"high\")
   (pgq/put q 100 \"medium/default\")
   (pgq/put q 200 \"low\")
   (pgq/put q 500 \"least\")"
  ([q item]
   (put q (get-in q [:config :default-priority]) item))
  ([q priority item]
   (when (not (nil? item))
     (let [{:keys [db schema table serializer]} (:config q)]
       (try
         (jdbc/insert! (get-db db) (qt-table schema table)
           {:name (name (:name q))
            :priority priority
            :data (s/serialize serializer item)})
         true
         (catch java.sql.SQLException _ false))))))

(defn locking-take
  "Lock and take item, returning a PGQueueLockedItem.

   usage: (locking-take q)

   example: (let [locked-item (pgqueue/locking-take q)]
               ; do some work here with item
               (pgqueue/delete-and-unlock locked-item))
               
   It is expected that pgqueue/delete and pgqueue/unlock 
   will later be called on the returned item and lock,
   respectively.

   See the pgqueue/take-with macro, which wraps up the
   use case for takers doing work and then deleting
   the item only after the work is safely completed."
  [q]
  (let [{:keys [db schema table serializer]} (:config q)
        [db db-pool-id] (get-db-and-id db)
        qtable (qt-table schema table)
        qname  (name (:name q))
        table-oid (table-oid db schema table)
        qlocks (get-qlocks-ids qname)
        qlocks-not-in (sql-not-in "id" qlocks)
        qlocks-not-in-str (when qlocks-not-in (str " and " qlocks-not-in))]
    (let [rs (jdbc/query db
               (sql-values
                 (str
                   "with recursive queued as ( \n"
                   "select (q).*, pg_try_advisory_lock(" table-oid ", cast((q).id as int)) as locked \n"
                   "from (select q from " qtable " as q \n"
                   "where name = ? and deleted is false order by priority, id limit 1) as t1 \n"
                   "union all ( \n"
                   "select (q).*, pg_try_advisory_lock(" table-oid ", cast((q).id as int)) as locked \n"
                   "from ( \n"
                   " select ( \n"
                   "  select q from " qtable " as q \n"
                   "  where name = ? and deleted is false \n"
                   qlocks-not-in-str
                   "  and (priority, id) > (q2.priority, q2.id) \n"
                   "  order by priority, id limit 1) as q \n"
                   " from " qtable " as q2 where q2.id is not null \n"
                   " limit 1) AS t1)) \n"
                   "select id, name, priority, data, deleted \n"
                   "from queued where locked \n"
                   qlocks-not-in-str
                   "limit 1") qname qname qlocks qlocks))
          item (first rs)]
      (when item
        (swap! *qlocks* assoc qname
          (conj (get-qlocks qname) {:lock-id (:id item)
                                    :db-id db-pool-id}))
        (->PGQueueLockedItem
          (->PGQueueItem q (:id item) (:name item) (:priority item)
            (s/deserialize serializer (:data item)) (:deleted item))
          (->PGQueueLock q table-oid (:id item)))))))

(defn delete
  "Delete a PGQueueItem item from queue.
   Delete behavior is controlled by the
   queue config option :delete in pgqueue/queue.
   If true, this actually deletes rows,
   otherwise, it sets the \"deleted\" flag to true.
   Returns boolean if a row was deleted.

   usage: (delete item)"
  [item]
  (let [q (:queue item)
        {:keys [db schema table delete]} (:config q)
        db (get-db db)
        qname  (name (:name q))
        qtable (qt-table schema table)]
    (if delete
      (> (first (jdbc/delete! db qtable ["name = ? and id = ?" qname (:id item)])) 0)
      (> (first (jdbc/update! db qtable {:deleted true}
                  ["name = ? and id = ? and deleted is false" qname (:id item)])) 0))))

(defn unlock
  "Unlock a PGQueueLock.
   Returns boolean.

   usage: (unlock lock)"
  [lock]
  (let [qname (name (get-in lock [:queue :name]))
        lock-id-1 (:lock-id-1 lock)
        lock-id-2 (:lock-id-2 lock)
        qlock (first (filter #(= (:lock-id %) lock-id-2) (get-qlocks qname)))
        qlock-db (get-db (get-in lock [:queue :config]) (:db-id qlock))]
    (swap! *qlocks* assoc qname  (remove #(= (:lock-id %) lock-id-2) (get-qlocks qname)))
    (:unlocked
     (first (jdbc/query qlock-db
              ["select pg_advisory_unlock(cast(? as int),cast(? as int)) as unlocked"
               lock-id-1 lock-id-2])))))

(defn delete-and-unlock
  "Delete and unlock a PGQueueLockedItem.
   This is a convenience function wrapping
   pgqueue/delete and pgqueue/unlock.
   Returns boolean \"and\" of above functions.

   usage: (delete-and-unlock locked-item)"
  [locked-item]
  (and
    (delete (:item locked-item))
    (unlock (:lock locked-item))))

(defn take
  "Take item off queue.
   Returns nil if no item available.

   usage: (take q)

   item is retrieved from the queue with the sort order:
    - priority (low number = high priority)
    - inserted order

   This function uses Postgresql's advisory locks 
   to ensure that only one taker gains access to the item,
   such that multiple takers can pull items from the queue
   without the fear of another taker pulling the same item.

   The item is retrieved from the queue with an advisory lock, 
   deleted (see pgqueue/queue for delete behavior), unlocked, 
   and returned.

   Also see pgqueue/take-with for use cases requiring the
   item to only be removed from the queue after successfully
   completing work."
  [q]
  (when-let [locked-item (locking-take q)]
    (delete-and-unlock locked-item)
    (get-in locked-item [:item :data])))

(defmacro take-with
  "Lock and take an item off queue, bind the taken item, 
   execute the body, and ensure delete and unlock after body.

   usage: (take-with [binding & body])

   binding takes the form [item q], where
   item is the binding name, and q is the queue.

   This macro uses Postgresql's advisory locks 
   to ensure that only one taker gains access to the item,
   such that multiple takers can pull items from the queue
   without the fear of another taker pulling the same item."
  [binding & body]
  `(let [locked-item# (locking-take ~(second binding))
         ~(first binding) (get-in locked-item# [:item :data])]
     (try
       (let [body-return# (do ~@body)]
         (when locked-item# (delete (:item locked-item#)))
         body-return#)
       (finally (when locked-item# (unlock (:lock locked-item#)))))))

(defn count
  "Count the items in queue."
  [q]
  (let [{:keys [db schema table]} (:config q)
        qtable (qt-table schema table)
        qname  (name (:name q))
        qlocks (get-qlocks qname)
        qlocks-not-in (sql-not-in "id" qlocks)
        qlocks-not-in-str (when qlocks-not-in (str " and " qlocks-not-in))]
    (:count
     (first
       (jdbc/query (get-db db)
         (sql-values
           (str "select count(*) from " qtable "\n"
             "where name = ? and deleted is false \n"
             qlocks-not-in-str) qname qlocks))) 0)))

(defn count-deleted
  "Count the deleted items in queue.
   These rows only exist when the :delete
   behavior in pgqueue/queue's config is set
   to false."
  [q]
  (let [{:keys [db schema table]} (:config q)
        qtable (qt-table schema table)
        qname  (name (:name q))]
    (:count
     (first
       (jdbc/query (get-db db)
         (sql-values
           (str "select count(*) from " qtable "\n"
             "where name = ? and deleted is true") qname))) 0)))

(defn purge-deleted
  "Purge deleted rows for the given queue.
   These rows only exist when the :delete
   behavior in pgqueue/queue's config is set
   to false.
   Returns number of rows deleted."
  [q]
  (let [{:keys [db schema table]} (:config q)
        qname (name (:name q))]
    (jdbc/with-db-transaction [tx (get-db db)]
      (first (jdbc/delete! tx (qt-table schema table)
               ["name = ? and deleted", qname])))))

