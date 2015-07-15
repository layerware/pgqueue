# pgqueue example

## Usage

Edit the config edn file with your db information.

Start up as many puters and takers in different terminals as you like.

```
# This continously puts onto the queue in a single thread
PGQUEUE_CONFIG=./example.config.edn lein run -m pgqueue.example.puter
```
```
# This continously takes from the queue in a single thread
PGQUEUE_CONFIG=./example.config.edn lein run -m pgqueue.example.taker
```

