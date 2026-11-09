## Context

The audit reproduced faults in existing carriers. Package pins are h2 0.4.20, h3 0.0.8, hyper 1.12.0, quinn 0.11.12 and tokio 1.54.1. Tests use current-thread Tokio unless a test declares multi_thread.

## Goals / Non-Goals

- Goal: Repair findings F1 through F7 with one fix commit per finding.
- Non-goal: Add H3 TCP MASQUE, CONNECT-IP, new DNS transports, external probes or device acceptance.

## Decisions

- Cancel only the incomplete xHTTP stream; retire a carrier only for a connection failure.
- Drive HTTP control streams before capability-dependent requests. Validate settings at the backend boundary. A peer without required Datagram support is rejected with a clear error or uses the existing bounded fallback; do not claim capsule-only H3 support.
- Use the existing h2 package if Hyper cannot expose all valid CONNECT success streams. Keep CA, ECH, socket protection and auth checks.
- Make capsule parsing expose errors and final truncation. CapsuleReader::push now returns a Result; all repository callers migrate together. This is an intentional internal Rust source API change. Unknown capsules are skipped with bounded storage.
- Restore the two missing boring-sys build inputs from the preceding audited revision as a separate build prerequisite. They are still referenced by the package manifest.
- Keep manifests and Cargo.lock under one coordinator. If a same-version h3 source patch is required, preserve upstream license and record its source and patch scope. No new production package is authorized or needed.

## Contracts and ownership

- No protobuf, JNI, persisted config or locale change. HTTP admission and stream error behavior become conformant.
- Coordinator owns task records, OpenSpec, Cargo manifests/lock, H2 code and integration gates.
- xhttp-repair owns ripdpi-xhttp and Naive documentation.
- h3-repair owns MASQUE H3 control/settings and UDP send admission.
- capsule-repair owns capsule.rs and response.rs plus separate tests. The coordinator integrates their H2/H3 callers after the workers complete.
- Every writer uses a separate worktree. No baseline, locale or golden edits.

## Risks / Trade-offs

- Carrier API changes can regress flow control or cancellation: use loopback byte exchange and cancellation regressions, then independent review.
- Invalid settings in pinned h3 require a narrow backend repair, not a package-wide update.
- Source gates may expose unrelated baseline failures; report them and do not weaken gates.

## Migration Plan

No data migration. Rebase the job branch onto origin/main, run affected gates on changed inputs, integrate with fast-forward and push main as authorized. Rollback is a normal revert of the affected fix commit. External CI/device/deployment evidence is reported separately from local tests.
