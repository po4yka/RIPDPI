## Context

The native probes already emit stage evidence with different granularity. Matrix and throughput are sequential; domain, service and TLS-profile checks contain independent connections. The stage scale is an evidence projection, not a new network probe.

## Goals / Non-Goals

- Goal: One vocabulary and conservative mapping for existing probe families, live transfer, history and text export.
- Non-goal: Invent stage timings, join unrelated connections, modify VPN activation or add probes and dependencies.

## Decisions

- Add typed Kotlin stage/lane/state models and pure projection functions in core/diagnostics.
- Stages: DNS, TCP, PROXY, TLS, HTTP_HEADERS, FIRST_BODY_BYTE, BODY, QUIC_RESPONSE. QUIC response is a separate lane, not a full handshake.
- Each supported probe maps into separate bounded lanes. Matrix repeats and throughput runs preserve attempt numbers. Missing duration stays null; elapsed time and duration remain distinct.
- Only explicit bounded numeric evidence and known tokens may populate the projection. Failure stages never imply unmeasured prior successes. An HTTP status proves headers arrived, not endpoint health.
- Compose uses an expandable stage view in result cards and history sheets. Live transfer uses the same vocabulary. Large/RTL text and labels communicate status without color alone.
- Reuse existing persistence and raw JSON/CSV source evidence. Add a bounded numeric/enum projection to text summary exports, with no native wire or database migration.

## Contracts and ownership

- Core writer owns new ConnectionStage*.kt projection/models and their tests in an isolated worktree.
- UI writer owns app source, tests, and all ten locale sets in a separate worktree.
- Integration writer owns summary/export integration, docs and task records. It serializes Gradle and final integration.
- No Rust, EngineContract, schema-version, dependency, lock, baseline or golden change is planned.

## Risks / Trade-offs

- Older probes have coarse evidence; unknown is more accurate than inferred success.
- Repeated readings can be malformed; accept matching bounded arrays only, without cross-attempt reuse.
- Stages describe one observed path and do not identify provider intent. Existing network-scope warnings remain visible.

## Migration Plan

No migration. Old reports are projected at read time; unsupported probe families retain their current view. Rollback removes only the presentation/projection. Gates: focused core and Compose tests, staticAnalysis, app/service locale lint, architecture checks, task validation and independent review. Android device and remote CI evidence remain separate.
