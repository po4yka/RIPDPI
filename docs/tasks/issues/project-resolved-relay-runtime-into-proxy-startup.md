---
id: SVC-1792830780372912
title: Project resolved relay runtime into proxy startup
kind: bug
status: review
area: service
priority: high
owner: relay runtime
parent: null
blocked_by: []
spec_mode: required
openspec_change: resolved-relay-proxy-projection
created: 2026-10-24
updated: 2026-10-24
status_detail: Reviewed source and exact 47a5ee708a497086eb29739ca17f35e164119846 genuine artifacts passed all four owned physical USB scenarios and cleanup. Final published hosted gates remain required before closure.
---

## Goal

Start the proxy with the resolved relay profile, UDP policy, and bound SOCKS endpoint used by the running relay. A saved UDP-enabled Hysteria2 profile must start when the global relay UDP flag is stale or disabled.

## Acceptance criteria

- A normal saved Hysteria2 start with global UDP disabled and resolved profile UDP enabled supplies UDP enabled and the bound endpoint to the actual proxy JSON envelope.
- A resolved profile with UDP disabled is rejected before proxy startup when the proxy requires UDP, even if global relay UDP is enabled.
- Imported Reality TCP profiles with Vision or Vision UDP443 retain UDP eligibility. XHTTP, flowless Reality, plain TLS VLESS, SSH, and Mieru remain UDP-ineligible. One shared pure sub-mode predicate serves mapping and service admission.
- Engine normalization retains the resolved UDP policy for Hysteria2, MASQUE, TUIC v5, AnyTLS, Trojan, Shadowsocks, and supported Reality TCP sub-modes. Unsupported kinds and raw sub-modes remain UDP-ineligible.
- Subprocess readiness telemetry uses a valid URI authority for IPv4, hostnames, and IPv6, and preserves the confirmed SOCKS port.
- Normal and race startup share rendering and identity, UDP, and endpoint checks. Missing or invalid readiness endpoints fail with cleanup.
- Selector identity, race promotion, WARP and AWG precedence, cancellation, and cleanup ownership remain covered by the complete affected service JVM suite.
- Targeted regression failure before the fix, passing service tests and static checks after it, independent review, and separate physical Hysteria and migration acceptance are recorded. Local source checks do not substitute for device evidence.

## Ownership

The relay-runtime owner edits SharedProxyRuntimeStack.kt, UpstreamRelaySupervisor.kt, their tests, SubprocessRelayTelemetryProjector, LocalProxyEndpoint, and their endpoint tests, a service-private runtime descriptor if required, and docs/architecture/CONFIG_CONTRACTS.md. The security owner edits SelectorRelayProfileMapping, its tests, the shared model predicate, RelayKindDescriptor, engine relay preference normalization, the two app failover predicate consumers, and capability tests in a separate worktree. The Android acceptance owner retains app tests, acceptance scripts, and device access. Integration and hosted checks belong to the parent owner. No native capability, guard, dependency, or storage changes are permitted.
