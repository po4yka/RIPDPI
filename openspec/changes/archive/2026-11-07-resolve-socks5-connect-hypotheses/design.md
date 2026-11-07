## Context

Task PRX-1794074402219640 follows local wire probes on 039f5fc0d. A stopped runtime could send upstream CONNECT after its terminal receipt. Upstream REP01..08 all became05. Absolute names failed either in the session length check or DNS terminal-label encoding.

## Goals / Non-Goals

- Goal: fix each confirmed defect in a separate commit with independent local wire regression evidence.
- Non-goal: change chained BND interpretation, add localhost family fallback, search lists, or infer unobserved platform behavior.

## Decisions

- Register a connected upstream TCP socket before SOCKS negotiation, retain its guard through auth/reply, and reject stages after shutdown using existing ConnectionAborted semantics. Test silent auth, late auth, and reply/drip cancellation with a live process. Document bounded SYN uncertainty separately; do not add a new async runtime.
- Map unproven TimedOut and WouldBlock to REP01. Preserve explicit validated upstream06 as protocol evidence.
- Keep a private typed upstream reply error in the runtime state handshake module. Project ErrorKind for internal classification, preserve validated REP at the final handler, and retain generic behavior for untyped OS PermissionDenied. Validate VER, RSV and assigned REP before projecting. Avoid public adapter API changes.
- Accept any nonempty domain at SOCKS framing. Strip exactly one terminal DNS root dot before the existing label checks. Keep interior empty-label rejection and existing size policy; do not add search semantics or unrelated DNS hardening.

## Contracts and ownership

- hypothesis_cancel owns routing/connect/socks.rs, the existing shutdown refusal helper visibility in routing/retry.rs, and cancellation tests first. This file is serialized: hypothesis_wire edits it only after the cancellation commit is integrated into its worktree.
- hypothesis_wire owns state/handshake.rs and error/reply tests. Timeout normalization and validated upstream REP propagation are separate commits. Root resolves handshake-handler overlaps if any.
- hypothesis_names owns ripdpi-session/src/socks5.rs, ripdpi-ws-bootstrap/src/query.rs, and their tests plus a separate runtime domain integration test.
- Root exclusively owns portfolio/OpenSpec files and generated board. No schema, lockfile, locale, golden, or architecture baseline lane changes.

## Risks / Trade-offs

- Tracking must cover the whole negotiation and reject sockets connected after forced drain; tests verify no late CONNECT bytes.
- Typed reply errors must survive routing wrappers and preserve local OS policy distinctions; independent upstream01..08 wire cases verify this.
- Domain normalization must strip only one root dot; malformed interior labels remain rejected.

## Migration Plan

No persisted data migration. Roll back one defect commit if needed. Before main integration run the affected crate tests with --locked, local network E2E including the relay lane, read-only independent review, just ci-gates, architecture-health check, and locked metadata. Record macOS-only and SYN/platform limits; no device, external-server, deployment, or hosted-CI proof is claimed.
