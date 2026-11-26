## Context

Portfolio task `DGN-1795704952088416` owns this change. Diagnostics reports mobile allowlist mode as its own verdict, separate from a generic outage and from DPI blocking, and narrows the candidate transports for that regime.

## Goals / Non-Goals

- Goal: deliver `Classify mobile allowlist mode as its own diagnostics verdict` with observable evidence.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Filed at proposal level. The probe thresholds and the transport narrowing table are design work and carry no recipes or parameters.
- The evidence is single-author and inferred, so the first design step is to check that the verdict can be falsified by a field report, not only by the author's reading.

## Risks / Trade-offs

- Incomplete evidence could create a false close. The archive wrapper requires all steps and evidence to be resolved.

## Migration Plan

Implement in an isolated worktree, run the named gates, then archive only through `taskctl` after review.
