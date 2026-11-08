## Context

Task PRX-1794125405826480 repairs the audited Transparent path. Accepted sockets do not identify the original bind scope: wildcard binds become concrete local addresses after accept. The public prebound-listener API can differ from requested config, so the actual socket must remain authoritative.

## Goals / Non-Goals

- Goal: fix the three reproduced findings and verify the pending-connect shutdown defect with a local regression.
- Goal: keep one fix per commit with independent observed tests.
- Non-goal: add a redirect installer, TPROXY, UDP support or a new Android mode.

## Decisions

- Apply IPv6 policy to the logical Transparent destination. Canonical IPv4 remains IPv4; do not apply this flag to relay carrier endpoints.
- Preserve the kernel-provided IPv6 scope. Do not infer a missing interface or change flow information without a verified ABI need.
- Use actual listening socket coverage for self checks. Do not infer listener ownership from a successful UDP bind. Preserve private resolved-domain admission and separate remote-peer restrictions.
- Reuse the existing cancellable socket connect loop for synchronous TCP. Keep protection before connect, observer callbacks, deadlines and socket options. Report genuine failures; classify cancellation during shutdown as an intentional stop before quality scoring or route fallback. Do not create an async runtime or an extra thread per connect.
- Default network and token behavior stays fail closed. No source rule, fixture or baseline gate is removed.

## Contracts and ownership

- Ingress worker owns F1 and F3 in proxy-runtime and necessary listener projections. Sockaddr worker owns privileged-ops fd conversion and its existing tests.
- Cancellation owns only adapter TCP connect control and runtime connect wiring. Ingress owns platform.rs listener metadata. The two sections are independent in separate worktrees; integration serializes their commits and validates the combined file.
- Integration owns planning, docs, combined checks and merges. Every writer uses an isolated worktree. Audit and review agents do not write.
- Cargo.lock, versions, public schemas, locales, API snapshots and golden fixtures are unchanged unless a required gate identifies a real owning change. Never extend a baseline to hide a regression.

## Risks / Trade-offs

- Wildcard and dual-stack coverage differs by platform. Inspect the real socket option and test exact, wildcard, prebound and mapped cases.
- Linux fd conversion is cfg-gated on macOS. Run Linux crate tests in an unprivileged container with no network and pinned offline dependencies.
- A socket backlog can create a deterministic Linux pending connect. Prove the regression at the runtime boundary before changing cancellation wiring.
- Real NAT and Android vendor behavior remain unverified. This limits deployment evidence, not the local source acceptance claims.

## Migration Plan

No data or wire migration. Revert the affected fix commit to roll back its behavior. Required gates: affected crate unit/integration tests, Linux fd and pending-connect regressions, all-target Clippy, formatting, locked metadata, architecture health and just ci-gates. Independent read-only review is required before commits.
