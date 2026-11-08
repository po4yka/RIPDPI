---
id: VPN-1794152738556618
title: Fix VPN flow owner attribution boundaries
kind: bug
status: doing
area: vpn
priority: high
owner: VPN flow attribution integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: vpn08-flow-owner-attribution
created: 2026-11-08
updated: 2026-11-08
---

## Goal

Fix VPN-08 findings F1 through F5, package-invalidation races and the discovered root/Lua retirement bypass. Preserve UDP transport and mapping ownership without destination-based app inference.

## Acceptance criteria

- Ordinary UDP UID age expires despite continuous traffic.
- Same-IP apps and synthetic DNS flows keep independent app identity.
- UID refresh preserves the same owner's UDP source port and pending mapping leases.
- Consuming UDP hooks retire obsolete reply context before returning, without a root probe.
- Late session/package results cannot alter current state.
- Resolver registration precedes TUN packet handling.
- Targeted behavior tests, required combined gates and independent reviews pass; device gaps remain explicit.

## Parallel ownership

Integration owns portfolio/spec records. UDP writer owns attribution expiry until its commit. Startup writer owns engine tunnel lifecycle files. Correlation/session writer owns Store/Bridge, service lifecycle, scope transport, learning and all serialized JNI/telemetry contracts after the UDP handoff. Each writer uses an isolated worktree. No shared dependency, locale, baseline or golden writer is assigned.

Integration also owns the runtime-policy direct_path_learning module, runtime-decision-ports policy.rs, runtime-services policy_port_impl.rs, runtime-api telemetry_sink.rs, Android telemetry/proxy sink adapters and NativeRuntimeSnapshot.kt contracts. The correlation writer retains proxy-runtime and TUN/JNI/service changes and receives this contract patch for combined validation. Startup ownership includes NativeBridgeWrappersTest.kt.

After F1 commit, the UDP writer owns udp_assoc and minimal LoopState collection fixtures in RIPDPI-vpn08-udp-scope. This slice must preserve concurrent source/app-scope associations and exact-generation retirement. The scope writer retains routing admission, cache/Auth/JNI/TCP and service code. The startup writer owns proxy-runtime propagation through an immutable connection state clone. Integration handed its tested lower-policy/telemetry patch to the scope writer for the combined F2 commit and no longer edits those files concurrently.
