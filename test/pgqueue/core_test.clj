(ns pgqueue.core-test
  (:require [clojure.test :refer :all]
            [pgqueue.core :as pgq]
            [pgqueue.serializer.nippy :as nippy-serializer]
            [pgqueue.serializer.fressian :as fressian-serializer]);
  (:import [pgqueue.core PGQueue PGQueueLockedItem]))

(def db-spec {:subprotocol "postgresql"
              :subname "//127.0.0.1:5432/pgtest"
              :user "pgtest"
              :password "pgtest"})

(def basic-config {:db db-spec})

(def configs
  {:basic basic-config
   :no-delete (merge basic-config
                {:schema "public"
                 :table "no_delete_queues"
                 :delete false})
   :nippy (merge basic-config
            {:schema "public"
             :table "nippy_queues"
             :serializer (nippy-serializer/nippy-serializer)})
   :fressian (merge basic-config
               {:schema "public"
                :table "fressian_queues"
                :serializer (fressian-serializer/fressian-serializer)})})

(deftest pgqueue-basic-tests
  (doseq [[name config] configs]
    (testing (str "pgqueue w/ config: " name)
      (let [q (pgq/queue :test config)]
        (testing "queue"
          (is (instance? PGQueue q)))
        (testing "put, take, count"
          (is (= 0 (pgq/count q)))
          (is (= true (pgq/put q 1)))
          (is (= 1 (pgq/count q)))
          (is (= 1 (pgq/take q)))
          (is (= 0 (pgq/count q)))
          (is (= nil (pgq/take q)))
          (is (= true (pgq/put q [1 2])))
          (is (= [1 2] (pgq/take q)))
          (is (= true (pgq/put q {:a 1})))
          (is (= {:a 1} (pgq/take q)))
          (testing "put nil"
            (is (= 0 (pgq/count q)))
            (is (= nil (pgq/put q nil)))
            (is (= 0 (pgq/count q)))))
        (testing "priority"
          (dotimes [n 50] (pgq/put q (- 50 n) n))
          (is (= 50 (pgq/count q)))
          (is (= 49 (pgq/take q)))
          (is (= 48 (pgq/take q)))
          (dotimes [n 47] (pgq/take q))
          (is (= 0 (pgq/take q)))
          (is (= 0 (pgq/count q)))
          (is (= nil (pgq/take q))))
        (testing "take-with"
          (pgq/put q :a)
          (pgq/take-with [item q]
            (is (= :a item))))
        (testing "put-batch, take-batch"
          (is (= true (pgq/put-batch q (range 4))))
          (is (= 4 (pgq/count q)))
          (is (= [0 1 2] (pgq/take-batch q 3)))
          (is (= 3 (pgq/take q))))
        (testing "delete behavior"
          (if (= name :no-delete)
            (do (pgq/purge-deleted q)
                (is (= true (pgq/put q :a)))
                (is (= 1 (pgq/count q)))
                (is (= :a (pgq/take q)))
                (is (= 1 (pgq/count-deleted q)))
                (is (= 1 (pgq/purge-deleted q)))
                (is (= 0 (pgq/count-deleted q))))
            (do (is (= true (pgq/put q :b)))
                (is (= 1 (pgq/count q)))
                (is (= 0 (pgq/count-deleted q)))
                (is (= :b (pgq/take q)))
                (is (= 0 (pgq/count-deleted q))))))
        (testing "destroying"
          (pgq/destroy-queue! q)
          (pgq/destroy-all-queues! config))))))


(defn put-in-new-thread
  [name config item]
  (deref
    (future
      (let [q (pgq/queue name config)]
        (pgq/put q item)))))

(defn take-in-new-thread
  [name config]
  (deref
    (future
      (let [q (pgq/queue name config)]
        (pgq/take q)))))

(deftest take-batch
  (testing "take-batch"
    (let [c (:basic configs)
          q (pgq/queue :test-take-batch c)
          item-count 5
          user-count (* 2 item-count)]
      (dorun
       (map (fn [i]
              (pgq/put q i))
            (range item-count)))
      (let [batch (pgq/take-batch q user-count)]
        (is (= item-count (count batch))
            "The batch doesn't produce more items than calls to put")
        (is (= (range item-count) batch)
            "The items are returned in the correct order")))))

(deftest pgqueue-concurrent-tests
  (testing "concurrent takers"
    (let [c  (:basic configs)
          q (pgq/queue :test-concurrent c)]

      (testing "locking-take"
        (pgq/put q :a)
        (pgq/put q :b)
        (let [locked-item (pgq/locking-take q)]
          (is (instance? PGQueueLockedItem locked-item))
          (is (= :a (get-in locked-item [:item :data])))
          (testing "different thread CAN NOT take locked item"
            (is (= :b (take-in-new-thread :test-concurrent c))))
          (testing "same thread CAN NOT re-take the item"
            (is (not (= :a (pgq/take q)))))
          (is (= true (pgq/delete (:item locked-item))))
          (is (= true (pgq/unlock (:lock locked-item))))
          ))
      
      (testing "take-with"
        (pgq/put q :c)
        (pgq/put q :d)
        (pgq/take-with [item q]
          (is (= :c item))
          (testing "nested take in different thread CAN NOT take same locked item"
            (is (not (= :c (take-in-new-thread :test-concurrent c)))))
          (testing "nested take-with in same thread CAN NOT re-take same locked item"
            (pgq/take-with [item q]
              (is (not (= :c item)))))))

      (testing "ensure failure during work is safe"
        (pgq/put q :failme)
        (is (thrown? clojure.lang.ExceptionInfo
              (pgq/take-with [item q]
                (throw (ex-info "failure when we have item locked" {})))))
        (is (= :failme (pgq/take q))))
      (pgq/destroy-queue! q)
      (pgq/destroy-all-queues! c))))


