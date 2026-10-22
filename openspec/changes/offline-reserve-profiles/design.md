## Context

The existing payload-aware selector change supplies strict two-origin HTTPS length/digest checks, bounded candidate rounds, cancellation fences, cooldown and outage backoff. ProxyGroup already serializes every member and its policy in AES-GCM protected private preferences; selector choice is durably stored separately. Reuse these owners rather than storing historical credentials in a second cache.

## Goals / Non-Goals

- Goal: accepted reserves survive offline restart, rejected refresh and persistence failure, and can drive positive payload-qualified failover.
- Goal: obsolete asynchronous updates cannot publish a new generation over a changed source or catalogue.
- Non-goal: server deployment, new probe endpoints, upload protocol extensions, cross-repository schema changes, UI, AWG multi-store transactions, cryptographic primitives, historical rollback of removed credentials or proof from a filtered network.

## Decisions

- Defer catalogue loading until the guarded startup boundary, so unreadable storage produces retryable failed readiness instead of aborting dependency construction.
- Read an existing sealed catalogue strictly. Absent data remains an empty catalogue; decryption/authentication errors remain errors. Keep the existing encryption key and wire shape. Ciphertext and metadata share one editor commit.
- Store the complete accepted current generation; never revive profiles removed by a later successful authoritative update. Keep delivery permission and transport credentials separate.
- Treat a refresh with no activatable relay profiles as invalid unless it contains a supported non-relay profile set. Do not mark unchanged old reserves healthy after discarding unsupported parsed nodes.
- Bind publication and failure metadata to the observed group snapshot inside the repository's serialized update boundary. A stale result is explicit and must not notify callers of an installed update. Preserve existing user intent and deletion fences; the independently persisted AWG catalogue retains its existing lifecycle and is outside this selector-reserve transaction.
- Exercise fresh repository instances over persistent test storage, the production parser/coordinator and selector, and real local HTTPS/SOCKS payload transfers. In-memory orchestration tests are not AndroidKeyStore or physical-device evidence.

## Contracts and ownership

- Parent: portfolio/spec/verification/docs, core-service shared socket fixture and test dependencies, combined integration and offline end-to-end test.
- Refresh worker: app subscription refresh coordinator, focused refresh tests and necessary caller handling. After persistence handoff, owns the reviewed deferred-load fix in ProxyGroupStores, guarded startup and their regression tests.
- Persistence worker: ProxyGroupStores and focused runtime-state persistence tests, handed off before startup edits; subsequently owns the isolated offline payload integration test.
- Parallel writers use separate worktrees rooted at the same reviewed plan commit. Shared task files, dependencies and generated assets have one parent writer. No Rust/JNI/protobuf changes.
- Validation: affected runtime-state and core-service JVM tests, app subscription/selector JVM tests, staticAnalysis, task-check, architecture-health; all compiler-backed top-level runs use build-gate with at most four workers.

## Risks / Trade-offs

- Strict storage errors can expose an existing corrupt catalogue instead of showing an empty list; this is intentional data-loss prevention and must not log credentials.
- Conservative snapshot publication may discard a result concurrent with an edit; the user can refresh again, while the accepted offline set remains authoritative.
- Unit restart tests verify catalogue and selector semantics; real AndroidKeyStore, app process death and network-switch acceptance need device tests and are reported separately.
- The PR is stacked on the existing payload-aware selector PR. Its full combined behavior depends on that implementation; the separate deployment PR remains unchanged.

## Migration Plan

No storage field, key, schema or wire format changes. The commit is reversible at source level. Existing valid encrypted groups load unchanged. Do not restore or rewrite corrupted private state as part of this change. No deployment follows from merging the client source.
