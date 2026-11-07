# Change: Use a bounded flume queue for WorkerReaper

Task ID: `DGN-1794036659395595`

## Why

WorkerReaper must support multiple consumers and bounded enqueue waits without a handwritten spin loop. The 2026-11-07 audit identifies this as finding 14 at source revision 202f970.

## What Changes

- Use existing flume::bounded, cloned receivers, and send_timeout. Keep the domain owner of JoinHandle and pending counters.
- Independent completed workers are joined concurrently without head-of-line blocking.
- A full or disconnected queue returns the original JoinHandle within the existing bounded enqueue policy.
- Correct only the specified defect or infrastructure duplication. No other breaking change is planned; Keep queue capacity 64, the 5 ms enqueue budget, returned JoinHandle ownership on rejection, worker-start failure handling, and shutdown order.

## Capabilities

### New Capabilities

- `standard-components/use-flume-for-worker-reaper-queue`: WorkerReaper must support multiple consumers and bounded enqueue waits without a handwritten spin loop.

### Modified Capabilities

- None. This delta records the previously unspecified contract for the audited path.

## Impact

- `native/rust/crates/ripdpi-monitor-engine/src/session/reaper.rs`

Use only existing platform or workspace libraries. Check the direct module dependency before editing. The parent epic owns programme order and shared lanes. No external publication, deployment, or unrelated protocol change is included.
