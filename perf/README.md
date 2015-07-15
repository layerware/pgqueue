# pgqueue perf

## Usage

Edit the config edn file with your db information.

The performance test does iterations of "put" and "take" runs
using Clojure's future and deref to do the work in multiple threads.

```
PGQUEUE_CONFIG=./perf.config.edn lein run -m pgqueue.perf
```
