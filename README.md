# pgqueue

A Clojure durable queue implementation on top of postgresql using
postgresql's advisory locks to provide non-blocking take,
such that concurrent workers do not block each other.

## Installation

[Leiningen](https://github.com/technomancy/leiningen) dependency information:

[![Clojars Project]
(http://clojars.org/com.layerware/pgqueue/latest-version.svg)]
(http://clojars.org/com.layerware/pgqueue)

## Usage

```clj
(require '[pgqueue :as pgq])

; Define a queue config
(def q-config {:db {:subprotocol "postgresql"
                    :subname "//127.0.0.1:5432/pgtest"
                    :user "pgtest"
                    :password "pgtest"}})

; Create a queue with a name and config
(def q (pgq/queue :my-queue q-config))

; Put items on the queue
(pgq/put q :a) ;=> true
(pgq/put q :b) ;=> true

; How many?
(pgq/count q) ;=> 2

; Take items off the queue
(pgq/take q) ;=> :a
(pgq/take q) ;=> :b
(pgq/take q) ;=> nil

; Safely take item from queue using pgqueue/take-with.
; If the work in the body crashes, the item is
; unlocked for other workers to take it instead.

; successful work
(pgq/put q :success)
(pgq/take-with [item q]
  ; item is locked
  ; do work here
  (Thread/sleep 20))
  ; item is unlocked/deleted

; failure case
(pgq/put q :failme)
(pgq/take-with [item q]
  ; item is locked
  (throw (ex-info "FAIL!" {}))) ;=> ExceptionInfo FAIL!
  
; it's OK, item is safe for the next taker:
(pgq/take q) ;=> :failme

```

## Documentation

[API Docs] (http://layerware.github.io/pgqueue)

## License

Copyright © 2015 Layerware, Inc.

Distributed under the [Apache License, Version 2.0] (http://www.apache.org/licenses/LICENSE-2.0.html)
