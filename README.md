# pgqueue

A Clojure durable queue implementation on top of postgresql using
postgresql's advisory locks to provide non-blocking take,
such that concurrent workers do not block each other.

## Installation

[Leiningen](https://github.com/technomancy/leiningen) dependency information:

```clj
[com.layerware/pgqueue "0.4.1"]
```

## Recent Changes

 - 0.4.1 fix (harmless) warning about rollback w/ autocommit on
 - 0.4.0 ```pgqueue``` namespace moved to ```pgqueue.core``` per [Clojure style guidelines](https://github.com/bbatsov/clojure-style-guide#no-single-segment-namespaces)
 - 0.3.4 Fixes reconnect bug
 - 0.3.3 Added ```:analyze-threshold``` option to ```pgqueue.core/queue```

## Usage

```clj
(require '[pgqueue.core :as pgq])

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

;; Put nil is a no-op
(pgq/put q nil) ;=> nil

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


;; pgqueue is also a priority queue
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


;; Put a batch
(pgq/put-batch q [1 2 3]) ;=> true

;; Take a batch
(pgq/take-batch q 3) ;=> [1 2 3]
   
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
a strategy that locks a row for update.

See the perf project in this repository to run a performance test
on your hardware.

Here are the results from a Linode 2048 
  (2GB RAM, SSD, 2 x Intel(R) Xeon(R) CPU E5-2680 v3 @ 2.50GHz)
using the following JVM options in project.clj:
```
jvm-opts ^:replace ["-Xmx1g" "-Xms1g" "-server"]
```

```
PGQUEUE_CONFIG=./perf.config.edn lein run -m pgqueue.perf
pgqueue perf test

       put     100 integers...    483ms duration   0.207/ms (   207/s) avg rate
      take     100 integers...    495ms duration   0.202/ms (   202/s) avg rate
 put-batch     100 integers...     51ms duration   1.961/ms (  1961/s) avg rate
take-batch     100 integers...    192ms duration   0.521/ms (   521/s) avg rate

       put    1000 integers...   1265ms duration   0.791/ms (   791/s) avg rate
      take    1000 integers...   3171ms duration   0.315/ms (   315/s) avg rate
 put-batch    1000 integers...    428ms duration   2.336/ms (  2336/s) avg rate
take-batch    1000 integers...   1779ms duration   0.562/ms (   562/s) avg rate

       put   10000 integers...   9312ms duration   1.074/ms (  1074/s) avg rate
      take   10000 integers...  33683ms duration   0.297/ms (   297/s) avg rate
 put-batch   10000 integers...   2419ms duration   4.134/ms (  4134/s) avg rate
take-batch   10000 integers...  20792ms duration   0.481/ms (   481/s) avg rate
```


## License

Copyright © 2015 Layerware, Inc.

Distributed under the [Apache License, Version 2.0] (http://www.apache.org/licenses/LICENSE-2.0.html)
