## Context

The current load coroutine ignores download success and timing.

## Goals / Non-Goals

- Goal: require measured transfer and RTT overlap.
- Non-goal: prove full link saturation or extend the network probe budget.

## Decisions

- Record byte-read progress and monotonic transfer times.
- Run blocking HTTP load on Dispatchers.IO concurrently with RTT probes.
- Classify only complete RTT samples inside the first-to-last-byte interval.

## Contracts and ownership

- Home evidence agent owns app augmentation and related helper/tests. No schema changes.

## Risks / Trade-offs

- Very short downloads produce UNKNOWN; this avoids unsupported good grades.

## Migration Plan

No migration. Revert to roll back. Run deterministic JVM tests and staticAnalysis.
