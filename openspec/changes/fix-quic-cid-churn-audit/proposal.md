# Change: Fix CID churn audit regressions

Task ID: `ENG-1794637897912969`

## Why

CID churn accepts an unbounded count, repeats IDs within a burst, and can emit an oversized IPv4 datagram. These defects can block the UDP runtime or prevent the original payload from being sent.

## What Changes

- Reject CID churn counts and cumulative chain counts above 64. Bound internally constructed plans too.
- Use distinct DCIDs across CID churn steps in one plan. Keep packet protection coherent.
- Produce the requested Initial size across QUIC Length field boundaries, including 65507 bytes.

## Capabilities

### New Capabilities

- `quic-cid-churn-safety`: bounded synthetic Initial generation and exact sizing.

### Modified Capabilities

- None.

## Impact

Kotlin strategy validation, native proxy configuration, UDP planning, and QUIC packet construction change. Previously accepted excessive counts become errors. No schema, dependency, JNI, storage, live migration, or root-mode change is required.
