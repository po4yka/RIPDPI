# Change: Mirror observability topology schema v2

Task ID: `DAT-1790868146040476`

## Why

The resource-bounded observability producer publishes topology schema v2, while
the client test-resource mirror still contains v1. Producer contract-sync fails
until the exact mirror reaches the client's default branch.

## What Changes

- Mirror only `observability-topology.schema.json` from producer revision
  `c7ef9bd519c28841f0c0b74256692fece1aa692a`.
- BREAKING contract: v2 replaces `sentinels` and node `host_class` with `observer`
  and `capabilities`, and bounds the topology to 1–10 nodes.
- Keep Android and native runtime behavior unchanged; this is not runtime
  observability support.
- Repair the pre-existing hosted API snapshot failure: normalize rustdoc's Arc
  path alias and record the already-existing `Dissect::tcp_mss` field in its
  owning strategy-trait snapshot, with regression coverage for real API drift.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `data/deployment-contract-mirrors`: mirror the producer's v2 topology exactly.

## Impact

- One test-resource schema, the bounded API snapshot check repair above, and
  required task/specification records.
- No new dependency, runtime parser, persisted-data migration, or deployment.
