## Context

See proposal.md and the TRN-08 audit. Existing factory create may return only configuration. A cached session can outlive eviction through active lease Arcs. Registered owner close already completes all joins before returning terminal errors in the three production implementations.

## Goals / Non-Goals

- Goal: repair F1, F2, F3 and confirmed H1/H3 boundaries with independent regression observations. Resolve H2 against the actual runtime owner.
- Non-goal: new protocols, idle probes, credential pooling, dependencies, physical or external network acceptance.

## Decisions

- F2 first: reserve the lease in the same critical section as cache selection/publication. Keep a per-cached-generation count plus the existing global health count. Retry acquires its lease permit before cache selection.
- F1 next: hold a shared eight-slot establishment gate through session create and initial open for both APIs, including reused-session opens. This conservative bound can also queue logical stream requests; it does not limit established data transfer. Always release the old attempt gate before retry lease acquisition.
- F3: treat resolved OwnedSession::close as terminal even on error. Remove the resource owner and retain only the first shared io::Error. A cancelled close remains registered.
- H3 internal factory contract: datagrams_use_shared_session defaults true; create_datagram_session defaults to create_session. VLESS overrides both to create an uncached config-only wrapper with no yamux. Independent UDP guards never change cached stream usage.
- H1 internal open result: add RelayOpenError with Closed and Backend variants. Relay-core maps Closed to io::ErrorKind::NotConnected and preserves backend io errors. Close semaphores and persistent shutdown notification before cleanup; cancel active opens and drain their opening guards before factory.shutdown. Reject cache publication under the closed state lock.
- H2: verify relay-android run_to_completion, which owns the Tokio runtime and calls shutdown_timeout after session.run. Abort-only session Drop is not a join barrier by itself. Add a real asynchronous carrier teardown regression at the owning boundary; add join plumbing only if that regression exposes an actual failure.

## Contracts and ownership

- Root owns task/OpenSpec artifacts, H1, combined gates, generated public API snapshots and integration.
- Idle worker owns F2 in relay-mux state/health/lease/pool and dedicated regression tests in an isolated worktree.
- Dial worker owns F1 after F2, then H3 in separate commits/worktrees based on the accepted combined tree.
- Cleanup worker owns F3 in relay-tls-transports session_registry and its tests in an isolated worktree. It also investigates the late-create cleanup cancellation boundary.
- Serialize relay-mux pool.rs: F2, then F1, then H3/H1. Root resolves combined edits. Workers do not edit portfolio, OpenSpec, generated snapshots, Cargo.lock, versions, locales, schemas, or goldens.
- Existing wire/JNI/config/storage contracts and authentication remain unchanged. The internal Rust pool API change requires regenerated API snapshots through the owning script.

## Risks / Trade-offs

- Conservative opening admission can add queueing for multiplexed logical opens; established streams remain unaffected. Real loopback concurrency tests and retry saturation tests prevent over-admission and lock-order deadlock.
- Terminal shutdown must not wait on long-lived established leases, only incomplete opens and factory-owned cleanup. Cancellation tests cover each wait.
- Resource release after a terminal error must preserve its diagnostic kind/source and never discard unfinished joins. Weak ownership is the regression oracle.

## Migration Plan

No persisted migration or wire change. Revert each atomic repair independently if needed. Run targeted owning crate tests first, affected relay-mux/core/TLS/Android tests and all-target Clippy, independent pr-reviewer and async-cancel-safety passes, just ci-gates, architecture health, locked metadata, strict task validation, then authorized fast-forward main integration and push. No physical device, credential, release or deployment action is included.
