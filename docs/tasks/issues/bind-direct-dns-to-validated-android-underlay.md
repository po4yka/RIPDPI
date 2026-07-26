---
title: Bind direct DNS to a validated Android underlay
type: task
status: review
area: dns
priority: critical
owner: DNS direct-underlay serialized lane
parent: epic-fail-closed-android-vpn-policy-engine
blocks: []
blocked_by: []
created: 2026-07-22
updated: 2026-07-26
---

## Goal

Execute split-DNS `DIRECT` rules only through the validated Android underlay that produced the policy, without allowing a stale network, VPN network, or system-default fallback.

## Scope

- Add a service-owned immutable underlay lease with monotonic network and policy generations.
- Stage the selected non-VPN underlay on the replacement TUN builder, but keep
  the active runtime's committed lease unchanged until the replacement TUN is
  established and the old runtime is retired.
- Bind direct DNS sockets with `VpnService.protect()` followed by `Network.bindSocket()` while preserving file-descriptor ownership.
- Support bounded UDP/53 queries and TCP fallback for truncated answers.
- Suppress stale responses before MapDNS rewrite or cache insertion and reset resolver/cache state on generation changes.
- Keep unsupported or unavailable direct paths fail-closed on the encrypted proxy plane, with redacted bounded telemetry.

## Ship definition

- [x] A generation mismatch performs no protect, bind, connect, or cache mutation.
- [x] Missing or invalid non-VPN underlay cannot fall back to the Android system default.
- [x] Direct sockets use the exact order `protect -> duplicate -> bind -> close duplicate -> connect` and Rust retains ownership of the original descriptor.
- [x] A late response from underlay A is discarded after underlay B becomes current.
- [x] UDP and TCP fallback validate DNS ID and question before accepting a response.
- [x] UID admission occurs before direct, block, or proxy DNS routing.
- [x] Unit, JNI contract, native integration, architecture, and static-analysis gates pass locally.

## Work log

- 2026-07-22: Serialized ownership assigned before implementation. Stage C shipped the immutable native policy evaluator; `DIRECT` remains an explicit encrypted-proxy fallback until this task lands.
- 2026-07-23: Added the Android lease/JNI binder, direct UDP/TCP transport,
  generation-aware cache invalidation, redacted outcome counters, and local
  fault/race/UID/transport regression coverage. Replaced DNS-set correlation
  with a callback-authority token carried transiently through the fingerprint
  and runtime policy; the runtime DNS signature triggers one cold-start refresh
  when that token first appears. Moved to review pending the full local
  static-analysis, telemetry field-manifest, and specialist-review gates.
- 2026-07-26: Closed review findings around relay-host bootstrap, generation
  exhaustion, callback-registration ordering, pre-establish publication, and
  Android's nullable default-underlay contract. The affected Android tests,
  native unit/integration tests, strict clippy checks, architecture scanners,
  and full local static-analysis gate pass; the task remains in review for
  independent frozen-diff approval and integration.
- 2026-07-26: Split lease publication into prepared and committed state so a
  failed replacement establish aborts its staged policy without exposing it to
  the still-forwarding runtime. Added ordinary UDP and TCP MapDNS generation
  guards plus a sparse-cache allocator that reuses reclaimed slots without a
  full capacity scan.
- 2026-07-26: Reduced acceptance excludes physical checks that are not runnable
  in the current environment: the real-VPS AWG/NAT lane still lacks provider
  inputs, and Pixel LAN SO_BIND evidence remains dependent on a reachable
  fixture path that must not mutate the MacBook network. These are not counted
  as pass results. Current local evidence for this task is deterministic:
  `git diff --check`, `cargo fmt --check`, `cargo metadata --locked --no-deps`,
  `cargo test --locked -p ripdpi-tunnel-core --lib`, the `tun_e2e` target,
  `cargo test --locked -p ripdpi-tunnel-android --lib`, the full service unit
  suite, targeted engine/model tests, `staticAnalysis`, architecture health,
  FFI/unsafe/cross-language contract scanners, and independent
  correctness/async/JNI/golden/security/legal review passes.
