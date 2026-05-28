---
title: "Add tun2socks UID validation to close SO_BINDTODEVICE escape (kernel 5.7+)"
type: task
status: backlog
area: vpn
priority: high
owner: unassigned
parent: epic-fail-closed-android-vpn-policy-engine
blocks: []
blocked_by: []
created: 2026-05-22
updated: 2026-05-22
source_wiki_pages:
  - "[[android-so-bindtodevice-vpn-bypass]]"
linked_task: null
---

- [ ] #task Add tun2socks UID validation to close SO_BINDTODEVICE escape #repo/RIPDPI #area/vpn #status/backlog ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-tun2socks-uid-validation-against-so-bindtodevice-bypass`
- **Verify:** `./gradlew :core:service:testDebugUnitTest :core:engine:testDebugUnitTest`
- **Scope (only modify these + this file + the ledger):** `core/engine/**`, `core/service/**`, `native/rust/crates/ripdpi-tunnel-android/**`, `app/src/androidTest/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Motivation

On Linux kernel 5.7+ (predominantly Android 12+, API 31+), `SO_BINDTODEVICE` privilege was dropped — any unprivileged app can bind a socket directly to a network interface (e.g., `tun0`) and bypass all Android VPN split-tunneling routing rules. Standard tun2socks reads packets off the TUN interface but has no UID attribution, so any per-app split-tunnel enforcement done at the routing layer is invisible to it.

The TeapodStream project (referenced in `[[teapodstream-android-client]]`) implements a countermeasure: gVisor/Go tun2socks with per-packet UID validation via `ConnectivityManager.getConnectionOwnerUid()`. RIPDPI's `epic-fail-closed-android-vpn-policy-engine` covers the strategic class of work; this task is a concrete child task closing this specific escape vector.

> [!info] Dedup notes
> `ripdpi-tun-driver` crate exists. Adjacent open issue `adopt-android-17-system-split-tunnel-ui-via-action-vpn-app-exclusion.md` covers a DIFFERENT mechanism (Android 17 system UI for split-tunnel app exclusion, not kernel-level SO_BINDTODEVICE bypass). PR description must confirm UID validation is not already implemented in `ripdpi-tun-driver` or the JNI tun2socks bridge.

## Proposed change

1. Determine current state in PR description: does `ripdpi-tun-driver` (or the JNI tun2socks bridge in `core/engine/`) already perform UID validation? If yes, this task is dedup'd and should be marked `dropped`. If no, proceed.
2. Add per-packet UID lookup via `ConnectivityManager.getConnectionOwnerUid(protocol, localAddress, localPort, remoteAddress, remotePort)`. API available without root from Android Q+ (API 29).
3. Enforce allowlist at the tun2socks layer BEFORE forwarding to the SOCKS5 proxy:
   - TCP unauthorized: send RST to terminate the connection cleanly.
   - UDP unauthorized: drop packet; cache port binding to reduce repeat API calls; strict source binding to prevent routing loops.
   - ICMP: provide a toggle (kernel-level UID attribution unreliable for ICMP).
4. Add a measurement test in `appium/` or `journeys/` that opens a socket with `SO_BINDTODEVICE = "tun0"` and verifies the connection is RST'd post-countermeasure.

## Acceptance criteria

- [ ] PR description confirms current state of `ripdpi-tun-driver` UID validation (present or absent).
- [ ] If absent: per-packet UID validation implemented in the tun2socks layer.
- [ ] TCP unauthorized → RST; UDP → drop with port-binding cache; ICMP → configurable toggle.
- [ ] Integration test: synthetic app uses `SO_BINDTODEVICE=tun0`; without countermeasure, connection succeeds; with countermeasure, RST'd.
- [ ] Verified on kernel 5.7+ device (Android 12+) and kernel <5.7 device to confirm version gating.
- [ ] Verify via `adb shell cat /proc/net/tcp` that no leaked connection appears to the remote host post-countermeasure.

## Risks / open questions

- `getConnectionOwnerUid` adds latency per packet — UDP port-binding cache is the documented mitigation; tune cache size for typical workloads.
- ICMP UID attribution is unreliable in kernel; default to block + opt-in pass.
- Whether RIPDPI's existing tun2socks uses gVisor or a different userspace stack — implementation may need stack-specific adaptation.
- Scope boundary (per wiki): closes the `SO_BINDTODEVICE` escape but does not hide VPN presence from the OS (`tun0` interface name still queryable via `NetworkCapabilities`). See `[[platform-vpn-detection-april-2026]]` for the broader detection surface.

## References

- [[android-so-bindtodevice-vpn-bypass]] — wiki concept page with full mechanism + gVisor countermeasure architecture
- [[teapodstream-android-client]] — reference implementation
- Parent epic: `epic-fail-closed-android-vpn-policy-engine`
- Related (different mechanism): existing issue `adopt-android-17-system-split-tunnel-ui-via-action-vpn-app-exclusion`
