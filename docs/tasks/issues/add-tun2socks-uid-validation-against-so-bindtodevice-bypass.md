---
title: "Add tun2socks UID validation to close SO_BINDTODEVICE escape (kernel 5.7+)"
type: task
status: doing
area: vpn
priority: high
owner: TUN adversarial lane
parent: epic-fail-closed-android-vpn-policy-engine
status_detail: UID enforcement is wired in TCP/UDP data plane; physical SO_BINDTODEVICE=tun0 adversarial harness and recurring privileged evidence remain
blocks: []
blocked_by: []
created: 2026-05-22
updated: 2026-07-16
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

- [x] PR description confirms current state of `ripdpi-tun-driver` UID validation (present or absent). **Absent** — `ripdpi-tun-driver` is TUN open/configure only; `ripdpi-flow-app-attribution` calls `getConnectionOwnerUid` for attribution/learning, not as an enforcement gate. The userspace stack is `smoltcp` in `ripdpi-tunnel-core`, not gVisor/Go.
- [~] If absent: per-packet UID validation implemented in the tun2socks layer. **Decision core shipped + unit-tested** (`ripdpi_tunnel_core::uid_policy`): `UidFlowPolicy::evaluate(uid, proto)` / `admit(source, …)` returning `Allow`/`ResetTcp`/`DropUdp`, fail-closed by default, plus a `FlowUidSource` port mirroring `AppUidResolver` (off the hot path). The live smoltcp consultation at the admission seams (TCP `io_loop::tcp_accept::admission`, UDP `io_loop::udp_assoc::forwarding::ensure`) and the JNI `getConnectionOwnerUid` source are **device-gated** — an unverified data-plane gate either breaks all traffic or fails open silently.
- [~] TCP unauthorized → RST; UDP → drop with port-binding cache; ICMP → configurable toggle. **Verdict mapping implemented + tested** (UDP→`DropUdp`, TCP/other→`ResetTcp`). The actual smoltcp `abort()`/RST emission, the UDP drop + 5-tuple/port-binding cache, and the ICMP toggle are the device-gated data-path half.
- [ ] Integration test: synthetic app uses `SO_BINDTODEVICE=tun0`; without countermeasure, connection succeeds; with countermeasure, RST'd. **DEVICE-GATED** (kernel 5.7+; needs `tun0` + a real socket).
- [ ] Verified on kernel 5.7+ device (Android 12+) and kernel <5.7 device to confirm version gating. **DEVICE-GATED.**
- [ ] Verify via `adb shell cat /proc/net/tcp` that no leaked connection appears to the remote host post-countermeasure. **DEVICE-GATED.**

## Risks / open questions

- `getConnectionOwnerUid` adds latency per packet — UDP port-binding cache is the documented mitigation; tune cache size for typical workloads.
- ICMP UID attribution is unreliable in kernel; default to block + opt-in pass.
- Whether RIPDPI's existing tun2socks uses gVisor or a different userspace stack — implementation may need stack-specific adaptation.
- Scope boundary (per wiki): closes the `SO_BINDTODEVICE` escape but does not hide VPN presence from the OS (`tun0` interface name still queryable via `NetworkCapabilities`). See `platform-vpn-detection-april-2026` for the broader detection surface.

## Work log

- 2026-07-17: The corrected privileged job built successfully and reached the physical runtime, but the namespace peer raced IPv6 Duplicate Address Detection: its immediate `bind(2001:db8::2)` failed with `EADDRNOTAVAIL`, so no manifest was produced. Marked both static veth IPv6 addresses `nodad` before spawning the peer helper and added a source contract that preserves that ordering. The same Linux run exposed an `uninlined_format_args` lint in the evidence writer; the arguments are now captured directly. The task stays open pending a valid artifact from the rerun.

- 2026-07-17: The first integrated privileged job reached a native Linux runner but failed while compiling the new physical target: `CommandExt::groups` was unstable and `stop_path` was moved before use. Replaced the helper launch with stable `setpriv` isolation (empty supplementary groups, all capability masks zero, `NoNewPrivs=1`), fixed the move, and split CI into runner-owned compilation plus root-only runtime execution with evidence ownership restoration. The task stays open pending a valid physical artifact from the corrected lane.

- 2026-07-16: Added the recurring physical Linux gate: isolated IPv4/IPv6 veth peer, real `tun0`, distinct non-root allowed/denied client UIDs, direct-path controls, exact TCP/UDP payloads, TUN packet counters, exact TCP RST proof, zero-delivery UDP denial, strict redacted evidence validation, and deterministic orphan checks. The gate also exposed and fixed premature smoltcp socket removal (RST was lost), denied-UDP attribution-token retention, empty-allowlist fail-open construction, and native attribution registration sentinel handling. Repo-side checks can reach review, but this task remains open until the scheduled privileged job publishes a valid artifact for the integrated commit.

- 2026-07-16: Reassigned to the TUN adversarial lane. Current scope is the physical privileged proof: TUN/netns topology, TCP+UDP positive/negative controls, packet-path counters, IPv4/applicable IPv6, deterministic cleanup, and fail-closed CI evidence when CAP_NET_ADMIN/root/tun is unavailable.

- 2026-06-05: No UID enforcement exists at the tun2socks packet-forwarding layer. `ripdpi-tun-driver` is a TUN open/configure crate only. `ripdpi-flow-app-attribution` + `FlowAppAttributionStore.kt` call `getConnectionOwnerUid` for attribution/learning only — no RST, no UDP drop, no allowlist gate. No SO_BINDTODEVICE bypass countermeasure found in Rust or Kotlin. No integration test for this scenario in `appium/`. All acceptance criteria unmet; full implementation work remains.
- 2026-06-05: **Architecture resolved (open question in "Risks" closed):** the userspace stack is **`smoltcp`**, driven from `ripdpi-tunnel-core` (`session/`, `sessions.rs`, `io_loop.rs`, `classify.rs`) — NOT gVisor/Go. Concrete plan for the next (on-device) session:
  1. **Enforcement point:** add a `UidFlowPolicy` gate consulted at session *establishment* in `ripdpi-tunnel-core` (where `classify.rs` first sees a new TCP SYN / first UDP datagram), BEFORE a SOCKS session is opened. Verdicts: `Allow` / `ResetTcp` (smoltcp `abort()`/RST on the listening socket) / `DropUdp` (drop + cache the `(local,remote,proto)` binding to throttle lookups). Pure decision fn `(uid, proto, &allowlist) -> Verdict` is unit-testable in-crate; the smoltcp wiring is not.
  2. **UID source:** JNI callback to `ConnectivityManager.getConnectionOwnerUid(proto, local, localPort, remote, remotePort)` (API 29+, no root), reusing the resolver behind `FlowAppAttributionStore`. Must run off the per-packet hot path — cache per 5-tuple, mirror the UDP port-binding cache the task specifies. Honor `vpnservice-protect-invariant.md` and `network-fingerprint-privacy.md` (never log raw UID/IP — only the existing scope/dest digest).
  3. **Version gate:** only arm on kernel ≥ 5.7 (Android 12+/API 31+); below that the escape doesn't apply.
  - **Why not implemented here:** acceptance criteria 4–6 are device-gated (Appium `SO_BINDTODEVICE=tun0` flow, kernel 5.7+ *and* <5.7 device runs, `adb shell cat /proc/net/tcp`); a data-plane gate cannot be verified green without a device, and an unverified RST/drop path either breaks all traffic or fails open silently. Kept `backlog` pending an on-device session.
- 2026-06-05: Re-audit confirms all 6 acceptance criteria remain unmet. `rg UidFlowPolicy` finds no match in `native/rust/crates/`; `ripdpi-tunnel-core/src/classify.rs` has no UID policy gating; no `SO_BINDTODEVICE` integration test exists under `appium/` or `journeys/` (only doc references). Status unchanged: `backlog`.
- 2026-06-11: **Shipped the unit-tested decision core.** Added `ripdpi_tunnel_core::uid_policy` — `UidFlowPolicy` (`evaluate`/`admit`, `Allow`/`ResetTcp`/`DropUdp`) + the `FlowUidSource` port mirroring `AppUidResolver`. Fail-closed by default (enforcing blocks unattributable flows; `allowing_unresolved()` opts out); the `Default` is disarmed (passes every flow) so the gate never breaks traffic on an unverified path. 7 unit tests cover the matrix; no UID/IP logged (privacy). The pure module lives at `uid_policy.rs` rather than `classify.rs` because `classify.rs` is a UDP/DNS demux that never sees a TCP SYN — the gate seams are `io_loop::tcp_accept::admission` (TCP) and `io_loop::udp_assoc::forwarding::ensure` (UDP), documented in the module. `cargo nextest` + `clippy -D warnings` + `fmt` clean; pr-reviewer pass: sound. Criteria 4–6 (the `SO_BINDTODEVICE=tun0` flow, kernel 5.7+/<5.7 runs, `/proc/net/tcp`) remain device-gated; status `backlog` → `doing`.

## References

- android-so-bindtodevice-vpn-bypass — wiki concept page with full mechanism + gVisor countermeasure architecture
- teapodstream-android-client — reference implementation
- Parent epic: `epic-fail-closed-android-vpn-policy-engine`
- Related (different mechanism): existing issue `adopt-android-17-system-split-tunnel-ui-via-action-vpn-app-exclusion`
