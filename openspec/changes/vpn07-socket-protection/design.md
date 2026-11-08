## Context

Task VPN-1794152756242597 repairs six VPN-07 findings. The current UID exclusion reduces routing-loop risk but does not replace per-socket protection. The one-byte SCM_RIGHTS protocol and JNI method signatures are established contracts.

## Goals / Non-Goals

- Goal: restore protection policy, bounded requests, before-bind ordering and consistent cleanup.
- Goal: preserve non-root support, loopback exemptions and deliberate inactive/RAW_PATH operation.
- Non-goal: change wire formats, relay capability policy, dependency versions, golden fixtures or private-directory trust policy without a confirmed defect.

## Decisions

- Project captured runtime context into the effective proxy policy before socket-setting projections; preserve explicit CLI choices.
- Use the existing socket2 timeout-before-connect pattern for local UDS clients. Keep ownership in blocking closures through cancellation.
- Preserve typed stop outcomes at lifecycle boundaries. Only FullyReleased permits coordinator and protection release. A generic stop exception or timeout also leaves native completion unknown, so preserve the original cause and retain ownership for the serialized retry path. This refines the audit suggestion of unconditional cleanup, which could remove protection too early.
- Use the existing active protection provider for profile checks and refuse stale required leases.
- Protect UDP sockets before bind, using kernel local-address observations in regression tests.
- Set a server-side handshake deadline and guarantee ancillary batch closure in finally. Do not add a second server pool or unbounded cleanup queue.

## Contracts and ownership

- Native worker owns F1 proxy config/runtime projection and F2 subprocess/WS UDS clients.
- Lifecycle worker owns F3 session teardown and F4 Naive profile checks.
- Server worker owns F6 UDS server and confirmed ancillary-fd error closure.
- Primary owns F5 UDP factories, task/spec artifacts, combined gates, review, integration and separate commits.
- Primary owns Cargo.lock and manifest edges: the existing pinned socket2 package supplies unbound UDP sockets in two crates, and the existing pinned nix package supplies safe kernel address observations in tests. No new packages or versions are added. No writer changes JNI/protobuf schemas, locale sets, quality baselines or golden fixtures. Existing one-byte wire messages remain compatible.

## Risks / Trade-offs

- Pending cleanup can retain resources: use existing owner and retry mechanisms with explicit terminal evidence.
- Socket callbacks are synchronous: retain socket ownership and bound UDS syscalls rather than return with a borrowed fd still in use.
- Host tests do not prove Android routing: retain separate device acceptance claims and report unrun gates.

## Migration Plan

No persistent data migration is required. Ship atomic fixes with their regression tests. Rollback uses ordinary revert of the affected fix; do not disable protection or loosen security gates. Before integration run targeted Rust and Kotlin tests, lint, independent review, architecture contracts, locked cargo metadata and just ci-gates on the combined tree. Device checks use the leased emulator pool; physical or external-server checks require separate authorization.
