# pgqueue

A Clojure durable queue implementation on top of postgresql using
postgresql's advisory locks to provide non-blocking take,
such that concurrent workers do not block each other.

## Installation

[Leiningen](https://github.com/technomancy/leiningen) dependency information:

```clj
[com.layerware/pgqueue "0.2.0"]
```

## Usage

```clj
(require '[pgqueue :as pgq])

; Define a queue config
(def q-config {:db {:subprotocol "postgresql"
                    :subname "//127.0.0.1:5432/pgtest"
                    :user "pgtest"
                    :password "pgtest"}})

;; Create a queue with a name and config
(def q (pgq/queue :my-queue q-config))

;; Put items on the queue
(pgq/put q :a) ;=> true
(pgq/put q :b) ;=> true

;; How many?
(pgq/count q) ;=> 2

;; Take items off the queue
(pgq/take q) ;=> :a
(pgq/take q) ;=> :b
(pgq/take q) ;=> nil

;; Safely take item from queue using pgqueue/take-with.
;; If the work in the body crashes, the item is
;; unlocked for other workers to take it instead.

;; Successful case
(pgq/put q :success)
(pgq/take-with [item q]
  ; item is locked
  ; do work here
  (Thread/sleep 20))
  ; item is unlocked/deleted

;; Failure case
(pgq/put q :failme)
(pgq/take-with [item q]
  ; item is locked
  (throw (ex-info "FAIL!" {}))) ;=> ExceptionInfo FAIL!
  
;; it's OK, item is safe for the next taker:
(pgq/take q) ;=> :failme


;; pgqueue is also priority queue
;; For put with arity of 2, a default priority of 100 is used.
;;   (see pgqueue/queue docs to set a default priority for a queue)
;; For put with arity of 3, the second argument is a priority integer
;; where a lower value = higher priority; negative integers are ok
(pgq/put q 500 "least")
(pgq/put q 200 "low")
(pgq/put q 100 "medium")
(pgq/put q 1   "high")
(pgq/put q -10 "urgent")

(pgq/take q) ;=> "urgent"
(pgq/take q) ;=> "high"
(pgq/take q) ;=> "medium"
(pgq/take q) ;=> "low"
(pgq/take q) ;=> "least"
   
```


## Documentation

[API Docs] (http://layerware.github.io/pgqueue)


## Why use pgqueue?

pgqueue is a middle-ground queueing solution between file-based
durable queues and larger queue/messaging servers.

If you are already using postgresql for your database and don't
need a heavy-load queueing solution, pgqueue might be the right fit.

Benefits of pgqueue include:
 - concurrent, non-blocking take across multiple processes
   and across multiple JVM threads
 - safely take an item for work (pgqueue/take-with) and
   ensure that item is unlocked in the event of a crash/exception
 - backing up your postgresql database backs up your queues, too!


## Performance

Obviously, a queue implementation on a relational database is going
to perform more slowly than a dedicated queuing server.  That said,
pgqueue's use of postgresql's advisory locks is likely faster than
a strategy that locks a row via update of a table column.

See the perf project in this repository to run a performance test
on your hardware.

Here are the results on my weak laptop (i5-4200U CPU@1.60GHz×4, SSD)
using the following JVM options in project.clj:
```
jvm-opts ^:replace ["-Xmx1g" "-Xms1g" "-server"]
```

```
$ PGQUEUE_CONFIG=./perf.config.edn lein run -m pgqueue.perf
Put      100 integers...      414ms
Take     100 integers...      617ms
Put     1000 integers...      774ms
Take    1000 integers...     2196ms
Put    10000 integers...     6269ms
Take   10000 integers...    21074ms
```

We're looking at about 2ms for a take even on this weak hardware.

## License

Copyright © 2015 Layerware, Inc.

Distributed under the [Apache License, Version 2.0] (http://www.apache.org/licenses/LICENSE-2.0.html)
