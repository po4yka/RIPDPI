## Context

RLY-11 uses native russh 0.65.1 for TCP-only SSH. The audit found F1–F5 in transport ownership and Kotlin profile paths. The task is RLY-1794300186609700.

## Goals / Non-Goals

- Goal: enforce existing pin, credential, and cancellation contracts on all affected paths.
- Non-goal: add UDP, certificate authentication, a provider, or an external service.

## Decisions

- Reuse the existing SshProfileRoute with a process-local seed and explicit host-key confirmation for unpinned imports. Shared admission rejects unpinned durable activation. Do not silently trust a discovered key.
- Validate at the shared admission boundary before durable writes; preserve native fail-closed checks.
- Preserve the passphrase as supplied. Do not trim credentials.
- Divide the remaining observation budget between remaining addresses and continue only after recoverable failures.
- Keep native task ownership until worker termination. Pin checks must cover rekey, and cancellation must wake blocked channel delivery.
- Vendor the exact existing russh 0.65.1 source to fix rekey callback coverage and blocked channel delivery. Its public API cannot enforce the required rekey check from the wrapper. Keep the version and dependency graph unchanged, preserve licenses, and document the focused patch. The native writer owns the full dependency lane.

## Contracts and ownership

- Import writer owns admission, import routing, and related app/data tests for F2.
- Integration writer owns the app share mapper/tests for F3 and core:service observer/tests for F4.
- Native writer owns ripdpi-ssh, native tests, and serialized Cargo manifests, lockfile, and any SSH dependency patch for F1/F5.
- Planning writer owns the portfolio task and this change's artifacts. No schema, JNI descriptor, locale, golden, or baseline change is planned.

## Risks / Trade-offs

- Rekey changes can affect ordering or authentication: prove unchanged-key transfer and changed-key rejection against an independent peer.
- Cancellation can detach work: prove bounded close while the channel remains retained and unread.
- Import navigation can lose pending values: test explicit accept, reject, and unchanged active profile.
- A reduced per-address budget can fail slow peers: keep one shared overall deadline and test both fallback and cancellation.

## Migration Plan

No persistence migration is required. Make one correction commit per finding, followed by review and task closure. Revert the affected correction commit to roll back behavior. Before main integration run targeted Kotlin tests, cargo test -p ripdpi-ssh --locked, just ci-gates, python3 scripts/ci/check_architecture_health.py, and cargo metadata --manifest-path native/rust/Cargo.toml --locked. Record device and hosted CI evidence separately; do not claim them from host tests.
