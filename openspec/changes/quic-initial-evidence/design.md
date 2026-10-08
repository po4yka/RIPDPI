## Context

The probe sends a QUIC Initial and validates one response. It does not complete an HTTP/3 request.

## Goals / Non-Goals

- Goal: retain this measurement limit in every result.
- Non-goal: implement a new HTTP/3 probe.

## Decisions

Append two constant ProbeDetail values for success and failure. Existing dynamic detail maps need no schema change.

## Contracts and ownership

Controls worker owns ripdpi-diagnostics-runner connectivity/probes/quic.rs, ripdpi-monitor-engine execution/lanes/quic.rs and their tests. Root owns UI display. No lockfile, wire definition, golden or locale changes.

## Risks / Trade-offs

Older readers ignore new keys; existing outcomes remain compatible.

## Migration Plan

No migration. Revert this isolated change to roll back. Run the loopback QUIC tests, native runner and monitor engine suites with --locked.
