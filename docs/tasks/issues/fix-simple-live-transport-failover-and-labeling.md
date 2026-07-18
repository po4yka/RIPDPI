---
title: Fix Simple live transport failover and XHTTP labeling
type: task
status: review
area: vpn
priority: high
owner: Codex live transport remediation
parent: epic-fail-closed-android-vpn-policy-engine
blocks: []
blocked_by: []
created: 2026-07-18
updated: 2026-07-18
---

## Goal

Make the Simple flavor react to a genuinely unavailable active relay and identify an active XHTTP profile accurately without exposing endpoint or credential data.

## Evidence

- Physical Pixel 7, non-rooted, Github Simple Debug with the private device bundle.
- A Hysteria2 endpoint replaced by the TEST-NET address produced sustained native `silent_drop` failures under independent Chrome traffic, but `FailoverCoordinator` remained on Hysteria2 and never advanced to the already seeded AWG candidate.
- A deterministic single-profile XHTTP build transferred independent Chrome traffic through a distinct VPN egress while the Simple UI displayed the raw protocol kind `vless`.
- Raw endpoints, UUIDs, keys, auth values, and full external addresses remain outside this task file and Git.

## Ownership

- `app/src/simple/kotlin/com/poyka/ripdpi/failover/**`
- `app/src/simple/kotlin/com/poyka/ripdpi/ui/**`
- Simple-flavor string resources and focused Github Simple tests
- Redacted live-client validation report for this slice

## Acceptance criteria

- [x] A relay path whose SOCKS-confirmed egress is unavailable advances to the next candidate after the documented debounce even when relay-listener health remains nominal.
- [x] A successful SOCKS-confirmed egress does not switch because of unrelated native target failures.
- [x] The active transport contract distinguishes XHTTP from generic VLESS without endpoint or credential data.
- [x] Simple UI renders a localized XHTTP label and preserves an honest generic-VLESS fallback.
- [x] Regression tests cover the failed relay probe path, healthy confirmation path, transport-detail propagation, and UI label mapping.
- [x] Github Simple unit tests and applicable static-analysis/localization gates pass.
- [x] Physical-device retest confirms XHTTP labeling and endpoint-unavailability failover with independent app traffic.

## Work log

- 2026-07-18: Created from redacted physical-device evidence; implementation ownership assigned to the isolated live-transport remediation worktree.
- 2026-07-18: Added proxy-error suspicion with authoritative SOCKS confirmation and a privacy-safe active-transport descriptor.
- 2026-07-18: Github Simple tests, full debug unit tests, static analysis, native APK assembly, and Pixel retests passed for the task acceptance criteria; moved to review pending integration.
