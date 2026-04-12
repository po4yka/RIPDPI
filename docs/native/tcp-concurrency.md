# TCP Relay Concurrency

This note documents the current TCP relay threading model and the Phase 7 validation work.

## Current Model

The listener accepts sockets on a bounded worker pool.

- one worker thread owns the client connection after handshake
- the client-to-upstream relay half stays on that worker thread
- one auxiliary relay thread handles the upstream-to-client copy half

This is intentionally not a fully async relay. The outbound half still runs the blocking desync pipeline (`send_with_group(...)`), which needs direct socket access and stays on the worker thread.

## What Changed

The runtime no longer spawns both steady-state relay halves as separate threads per TCP flow.

Before:

- worker thread for the accepted client
- one outbound relay thread
- one inbound relay thread

Now:

- worker thread for the accepted client and outbound/desync path
- one inbound relay thread

That reduces the steady-state relay thread budget by one OS thread per active TCP flow without changing the desync behavior.

## Measurement Path

Phase 7 uses `native/rust/crates/ripdpi-runtime/tests/network_load.rs`:

- `proxy_connection_resource_budget`

The test writes `proxy_connection_resource_budget.results.json` into the native load artifact directory and records:

- established connection count
- whole-process baseline and steady-state thread/RSS counts
- `ripdpi-*` thread counts that isolate the proxy runtime from the local fixture server
- derived proxy thread growth per established connection
- derived process RSS growth per established connection

The CI assertion intentionally uses two layers:

- coarse whole-process growth bounds from the sampled RSS/fd/thread data
- a tighter `<= 2.25` per-established-connection budget on `ripdpi-*` threads only

That keeps the regression signal stable even though the load fixture itself also creates threads inside the same test process.

## Why There Is No Full Async Rewrite

The current evidence supports a bounded hybrid model, not a full async conversion:

- the inbound half is plain byte forwarding and can be moved off the hot worker path
- the outbound half still owns desync execution, socket mutation, and retry-sensitive blocking behavior
- a more aggressive async rewrite would increase risk in the most protocol-sensitive part of the relay path without measurement showing that it is required yet

Future work can revisit `io_uring` or task-based relay only if the load artifacts show the remaining two-thread budget is still a material bottleneck.
