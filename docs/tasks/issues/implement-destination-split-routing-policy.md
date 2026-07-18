---
title: Implement fail-closed destination split routing policy
type: task
status: doing
area: routing
priority: critical
owner: Codex serialized routing policy lane
parent: epic-fail-closed-android-vpn-policy-engine
blocks: []
blocked_by: []
created: 2026-07-18
updated: 2026-07-18
---

## Goal

Bridge ordered destination routing rules into the production TCP, UDP, and DNS data plane so the configured region-scoped direct / non-local tunneled policy is real, cache-independent, and fail closed.

## Evidence

- `split_tunnel_mode` currently controls Android per-app allow/exclude behavior only.
- Persisted domain, CIDR, geosite, geo-IP, port, and network rules are not consumed by the production runtime.
- The sing-box route importer preserves package rules but ignores destination route fields, `auto_route`, and `strict_route`.
- The active relay SOCKS upstream is attached to every existing proxy group, so an included application's destinations all use the relay rather than the requested destination split.

## Ownership and design constraints

- Use a dedicated worktree and serialized ownership for Kotlin/Rust wire files and goldens.
- Keep destination egress (`Tunneled`, `Direct`, `Block`) separate from adaptive desync-group selection.
- Compile an immutable, bounded engine-owned policy from ordered repository rules; first match wins.
- Unknown destinations default to the tunneled outbound while it is active. Invalid or missing geo data must never create an unintended direct path.
- Apply the same decision to TCP, UDP, and DNS. Route hints, DNS cache, retries, and transport failover must not change the selected egress action.
- The native proxy schema remains version 2: this is an additive defaulted section. Do not bump relay, tunnel, AppSettings protobuf, or Room schema solely for this bridge.

## Acceptance criteria

- [ ] Kotlin mapper covers ordered exact/suffix/geosite, CIDR/geo-IP, destination port, and transport matchers with atomic validation.
- [ ] Unsupported profile/group, package/process, and source-port semantics are rejected or surfaced rather than silently weakened.
- [ ] Additive Kotlin/Rust routing wire defaults old payloads to an inert policy and preserves the section across rewrite/replay paths.
- [ ] Native TCP and UDP enforce `Tunneled`, `Direct`, and `Block` before opening the wrong socket; Block opens no egress socket.
- [ ] DNS classification shares the same policy and defaults unknown or failed classification to the protected route.
- [ ] Tests prove IPv4/IPv6 parity, shared-IP hostname independence, cache/hint independence, retry/failover preservation, malformed-policy rejection, and missing-geo fail-closed behavior.
- [ ] Android runtime rebuilds when the enabled destination policy changes.
- [ ] Physical Pixel dual-vantage evidence covers at least ten Russian and ten foreign destinations in forward and reverse order after a full tunnel rebuild.
- [ ] Full tunnel sends the full cohort through the tunneled outbound; split sends region-matched traffic direct and non-local or unknown traffic through the tunneled outbound; DNS shows the same policy with no direct window.

## Planned atomic commits

1. Compile destination routing rules into an engine DTO.
2. Add the defaulted native routing wire and contract tests.
3. Enforce egress decisions in TCP and UDP.
4. Align DNS routing with the destination policy.
5. Activate managed category/geosite routing and runtime rebuilds.
6. Add Android instrumentation and physical dual-vantage evidence.
