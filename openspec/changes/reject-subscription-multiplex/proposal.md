# Change: Reject unsupported subscription multiplex modes

Task ID: `DAT-1791477482125477`

## Why

A server can publish a flow-less VLESS REALITY outbound with enabled smux. Import currently loses multiplex and adds Vision flow, so the stored profile has a different wire mode.

## What Changes

- Reject enabled multiplex before mapping an outbound to a selectable profile.
- Preserve omitted and explicit empty REALITY flow as no-flow; keep explicit Vision.
- Report rejection through the existing unsupported transport reason with a fixed multiplex detail.

## Capabilities

### New Capabilities

- `subscription-multiplex-import`: Preserve protocol intent and report unsupported multiplex on import.

### Modified Capabilities

- None.

## Impact

- Runtime-state subscription parser and core data JVM tests. No new dependencies, wire schema, storage schema, or native implementation.
