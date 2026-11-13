## Context

Task RST-1794557321446019 addresses F1-F9 from the DPI-14 audit. The current source retains all nine defects.

## Goals / Non-Goals

- Goal: preserve payload bytes, packet lengths, identifier uniqueness, profile fields, and safe send progress.
- Non-goal: add strategy options, alter other DPI methods, enable root, or probe devices and external servers.

## Decisions

- Keep each finding in a separate Conventional Commit and integrate with fast-forward main updates after combined validation.
- Use existing TCP_REPAIR queue insertion. Classify failures before emission separately from failures after possible emission; unknown in-flight failures are uncertain.
- Use protected and marked raw sockets for MTU preflight. Do not weaken VPN protection or disable PMTU.
- Bump helper wire protocol and command namespace to v4 in lockstep with Kotlin. Reject old peers before privileged effects.
- Keep fragment ID reservations in the real IP reassembly namespace for a bounded retention interval. Exhaustion rejects before send; eviction cannot release live IDs.
- Enforce original reassembled lengths for both IP families, including IPv6 extensions.

## Contracts and ownership

- primary: planner eligibility, desync-runtime error/progress and runtime-adapter projection, combined tests, task/spec artifacts and integration.
- validation agent: Kotlin StrategyChainValidation and StrategyChainsIpFragTest; Rust proxy-config chain validation and conversion tests (F1).
- wire agent: privileged TCP fragment emission and raw-fragment MTU checks (F2/F7), runtime IPv4 fragment reservation (F8), ripdpi-ipfrag length guards/tests (F9).
- helper agent: root-helper protocol/command/Kotlin version lane, helper handlers, runtime-platform helper client/transport and TCP fragment request projection (F5/F6). It owns executor profile projection only; primary edits its fallback guard after integration.
- Cargo.lock, dependency versions, locales, baselines and golden fixtures are not assigned for change. Schema/version/API snapshot changes are serialized to the helper agent and validated on the combined tree.
- Writers use distinct worktrees and preserve all other work. Review agents do not edit files.

## Risks / Trade-offs

- Helper v4 is incompatible with v3: exact preflight validation prevents a silent field downgrade.
- ID reservation has finite capacity: safe rejection is preferable to an ambiguous fragmented datagram.
- Darwin cannot execute Linux TCP_REPAIR. Local contract tests and Android cross-compilation cannot establish live raw-wire acceptance.

## Migration Plan

Ship app and helper from the same source revision. Reject older helper responses. Roll back the complete app/helper pair if needed. No persistent data migration or dependency change is required. Validate narrow Rust and Kotlin tests, relevant contract/API gates, Android native compilation where available, independent packet reassembly, independent review, and just ci-gates before integration. Physical/root/external tests remain unexecuted unless separately authorized.
