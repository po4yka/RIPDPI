## Context

Task ID: `DGN-1794747967332880`. The seven DIA-16 findings affect Kotlin detection and presentation. The app excludes its own UID from its VPN. No new VPN path can be inferred from a socket constructor.

## Goals / Non-Goals

- Goal: Correct F1–F7 with seven separate implementation commits and local regressions.
- Non-goal: Add ICE, TURN, authentication, a new relay, public probes, root operations, or physical-device acceptance.

## Decisions

- Add APP_DEFAULT to CallTransportPath. The default client and default MTProto prober use that path. Keep VPN and UNDERLYING only for clients that actually own them. Preserve empty supportedPaths.
- Add CategoryEvaluation with COMPLETE default, DISABLED, UNSUPPORTED, UNAVAILABLE, and NOT_EVALUATED. Append the field to CategoryResult. Reuse translated neutral status labels. Non-complete JSON categories carry evaluation; existing complete outputs stay compatible.
- Validate a whole STUN message before extraction. Preserve legacy MAPPED extraction. Recognize known unexpected integrity attributes without treating them as authenticated evidence. WebRTC still requires XOR and delegates shared validation.
- Connect UDP sockets to the selected endpoint. Validate SOCKS5 envelope and expected port/domain. Numeric origins are checked for valid non-wildcard framing; domain resolution remains owned by the connected SOCKS relay.
- Address divergence is neutral evidence. Preserve path provenance in IP consensus and normalize numeric addresses. An absent comparable path is not evaluated.
- Follow the existing cancellation-aware socket pattern. Bound caller-visible DNS waits and cap in-flight blocking lookups. Use existing dependencies only. Retain documented one-shot sampling within the probe budget; do not infer failure from a different transaction.

## Contracts and ownership

- Modules: core/detection and app. No Rust crates, JNI, protobuf, database, configuration migration, or production dependencies change.
- Ownership and shared file sections are recorded in docs/tasks/issues/fix-dia16-stun.md. Root combines branches; workers do not edit main. Models, WebRtcLeakChecker, and CallTransportLeakChecker have serialized section ownership. No locale or golden changes are planned.
- F1 controls writer; F2 root; F3 wire writer; F4 transport writer; F5 root; F6 controls writer; F7 transport writer. Independent review is required before each behavior commit.

## Risks / Trade-offs

- New outcome/path values can reach exhaustive when sites. Search all callers and compile affected modules.
- Unauthenticated STUN remains unauthenticated. Correct parsing and peer matching do not prove a remote server is honest.
- Blocking platform DNS may finish after caller cancellation. A fixed in-flight limit prevents unbounded orphan work.
- Live Android and external compatibility evidence remains distinct from host tests. Do not claim it without an observed run.

## Migration Plan

No persistent schema migration is needed. Complete category defaults preserve current callers. Rollback uses ordinary revert commits. Run targeted regression tests first, core/detection unit tests, app relevant tests, staticAnalysis, just ci-gates, architecture health, and cargo metadata --locked before integration. Use the machine build gate and at most four workers. Record native-skip limits explicitly. Fast-forward main and push only after review and required gates; the user authorized both actions.
