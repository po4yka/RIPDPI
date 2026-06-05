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
updated: 2026-06-05
source_wiki_pages:
  - "android-so-bindtodevice-vpn-bypass"
linked_task: null
---

## Motivation

On Linux kernel 5.7+ (predominantly Android 12+, API 31+), `SO_BINDTODEVICE` privilege was dropped — any unprivileged app can bind a socket directly to a network interface (e.g., `tun0`) and bypass all Android VPN split-tunneling routing rules. Standard tun2socks reads packets off the TUN interface but has no UID attribution, so any per-app split-tunnel enforcement done at the routing layer is invisible to it.

The TeapodStream project (referenced in `teapodstream-android-client`) implements a countermeasure: gVisor/Go tun2socks with per-packet UID validation via `ConnectivityManager.getConnectionOwnerUid()`. RIPDPI's `epic-fail-closed-android-vpn-policy-engine` covers the strategic class of work; this task is a concrete child task closing this specific escape vector.

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
- Scope boundary (per wiki): closes the `SO_BINDTODEVICE` escape but does not hide VPN presence from the OS (`tun0` interface name still queryable via `NetworkCapabilities`). See `platform-vpn-detection-april-2026` for the broader detection surface.

## Work log

- 2026-06-05: No UID enforcement exists at the tun2socks packet-forwarding layer. `ripdpi-tun-driver` is a TUN open/configure crate only. `ripdpi-flow-app-attribution` + `FlowAppAttributionStore.kt` call `getConnectionOwnerUid` for attribution/learning only — no RST, no UDP drop, no allowlist gate. No SO_BINDTODEVICE bypass countermeasure found in Rust or Kotlin. No integration test for this scenario in `appium/`. All acceptance criteria unmet; full implementation work remains.
- 2026-06-05: **Architecture resolved (open question in "Risks" closed):** the userspace stack is **`smoltcp`**, driven from `ripdpi-tunnel-core` (`session/`, `sessions.rs`, `io_loop.rs`, `classify.rs`) — NOT gVisor/Go. Concrete plan for the next (on-device) session:
  1. **Enforcement point:** add a `UidFlowPolicy` gate consulted at session *establishment* in `ripdpi-tunnel-core` (where `classify.rs` first sees a new TCP SYN / first UDP datagram), BEFORE a SOCKS session is opened. Verdicts: `Allow` / `ResetTcp` (smoltcp `abort()`/RST on the listening socket) / `DropUdp` (drop + cache the `(local,remote,proto)` binding to throttle lookups). Pure decision fn `(uid, proto, &allowlist) -> Verdict` is unit-testable in-crate; the smoltcp wiring is not.
  2. **UID source:** JNI callback to `ConnectivityManager.getConnectionOwnerUid(proto, local, localPort, remote, remotePort)` (API 29+, no root), reusing the resolver behind `FlowAppAttributionStore`. Must run off the per-packet hot path — cache per 5-tuple, mirror the UDP port-binding cache the task specifies. Honor `vpnservice-protect-invariant.md` and `network-fingerprint-privacy.md` (never log raw UID/IP — only the existing scope/dest digest).
  3. **Version gate:** only arm on kernel ≥ 5.7 (Android 12+/API 31+); below that the escape doesn't apply.
  - **Why not implemented here:** acceptance criteria 4–6 are device-gated (Appium `SO_BINDTODEVICE=tun0` flow, kernel 5.7+ *and* <5.7 device runs, `adb shell cat /proc/net/tcp`); a data-plane gate cannot be verified green without a device, and an unverified RST/drop path either breaks all traffic or fails open silently. Kept `backlog` pending an on-device session.
- 2026-06-05: Re-audit confirms all 6 acceptance criteria remain unmet. `rg UidFlowPolicy` finds no match in `native/rust/crates/`; `ripdpi-tunnel-core/src/classify.rs` has no UID policy gating; no `SO_BINDTODEVICE` integration test exists under `appium/` or `journeys/` (only doc references). Status unchanged: `backlog`.

## References

- android-so-bindtodevice-vpn-bypass — wiki concept page with full mechanism + gVisor countermeasure architecture
- teapodstream-android-client — reference implementation
- Parent epic: `epic-fail-closed-android-vpn-policy-engine`
- Related (different mechanism): existing issue `adopt-android-17-system-split-tunnel-ui-via-action-vpn-app-exclusion`
