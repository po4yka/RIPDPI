---
id: VPN-1786264762917166
title: Add tun2socks UID validation to close SO_BINDTODEVICE escape (kernel 5.7+)
kind: feature
status: blocked
area: vpn
priority: high
owner: ICMP and MapDNS physical harness lane
parent: EPC-1786264762917557
blocked_by: []
spec_mode: required
openspec_change: vpn-1786264762917166-add-tun2socks-uid-validation-against-so-bindtodevice-bypass
created: 2026-05-22
updated: 2026-08-27
source_wiki_pages:
  - android-so-bindtodevice-vpn-bypass
linked_task: null
status_detail: Native UID admission and physical harness are implemented and locally verified. Pixel 8 Pro is attached (kernel 6.1, API 37); source-bound physical runs and socket-table evidence still require an authorized routed dual-stack fixture and APK/VPN test permission, plus a kernel <5.7 device.
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
- [x] If absent: per-packet UID validation implemented in the tun2socks layer. `UidFlowPolicy` is consulted at the live smoltcp TCP and UDP admission seams, the JNI `getConnectionOwnerUid` attribution source is registered with the native session, and readiness remains fail-closed until the bridge is installed. Unit, lifecycle, and physical harness coverage exercise the armed policy rather than only the decision enum.
- [~] TCP unauthorized → RST; UDP → drop with port-binding cache; ICMP → configurable toggle. TCP `abort()`/RST delivery, ordinary UDP drop/attribution-token retention, allowlist fail-closed construction, the default-deny ICMP policy toggle, and the MapDNS exact kernel-visible tuple admission boundary are implemented. Physical ICMP and MapDNS DNS evidence remains blocked by the current no-network/no-device permission.
- [x] Integration test: synthetic app uses `SO_BINDTODEVICE=tun0`; without countermeasure, connection succeeds; with countermeasure, traffic is denied. The platform-neutral unprivileged Linux process oracle and the separate-UID Android test process now prove the real-socket/TUN control and enforcement paths for TCP and UDP. On Pixel, IPv4 denial is reset/timeout and IPv6 denial is an exact unreachable-connect result; both require zero fixture delivery and passing post-denial liveness controls.
- [~] Verified on kernel 5.7+ device (Android 12+) and kernel <5.7 device to confirm version gating. Pixel 7/API 37/kernel 6.1 is verified; a pre-5.7 device remains unavailable.
- [ ] Verify via `adb shell cat /proc/net/tcp` that no leaked connection appears to the remote host post-countermeasure. **DEVICE-GATED.**

## Risks / open questions

- `getConnectionOwnerUid` adds latency per packet — UDP port-binding cache is the documented mitigation; tune cache size for typical workloads.
- ICMP UID attribution is unreliable in kernel; default to block + opt-in pass.
- Whether RIPDPI's existing tun2socks uses gVisor or a different userspace stack — implementation may need stack-specific adaptation.
- Scope boundary (per wiki): closes the `SO_BINDTODEVICE` escape but does not hide VPN presence from the OS (`tun0` interface name still queryable via `NetworkCapabilities`). See `platform-vpn-detection-april-2026` for the broader detection surface.

## Work log

- 2026-08-27: During final checks a Pixel 8 Pro became available (kernel 6.1, API 37). No physical run was performed: routed dual-stack fixture details and APK/VPN test permission remain pending, and no kernel <5.7 device is available. The integration lane also corrected the physical runner's build invocation: preserve the machine gate while removing its Gradle-rejected ambient Cargo jobs override, with an explicit two-job native budget. A behavioral command regression failed before this fix and passed afterward.

- 2026-08-27: Implemented the remaining source hardening: UID admission precedes raw TCP/UDP/MapDNS egress; queued packets retain their original lookup generation and a five-second deadline; pending TCP listeners own and retire lookup tokens; accepted smoltcp handles are reconciled to actual source tuples before cleanup; active retransmitted SYNs cannot steal token ownership; denied TCP gets a local IPv4/IPv6 RST; UDP attribution metadata is bounded to 64 exact tuples per association. Regression tests reproduced raw egress, stale-generation replay, pending-GC, listener-stealing, and duplicate-owner failures before their fixes. The Android runtime/acceptance bridge now shares a singleton activation epoch.
- 2026-08-27: Physical harness v4 now supports actual kernel >=5.7 and <5.7 profiles, capability-based backport behavior, live armed/disarmed state assertions, and timestamped `/proc/net/tcp{,6}` samples with a held positive-control socket. Evidence remains fail-closed on missing permissions, stale provenance, absent positive controls, or observed denied sockets. The v3 evidence contract is intentionally replaced; old evidence cannot qualify the new source. Samples do not claim continuous kernel tracing. No Android device was attached in this run, so current-source protocol, both-kernel, and socket-table acceptance remain blocked. Historical July results below are not proof for this revision.

- 2026-08-27: Android follow-up ownership also includes `FlowAttributionBridge.kt`: the runtime and acceptance gate currently receive separate unscoped instances. The isolated Android writer owns the minimal singleton binding correction and runtime-state evidence assertions; the integration lane continues to own all other production files and task artifacts.

- 2026-08-27: Remaining implementation ownership: the integration lane owns native UID admission and lifecycle fixes, this portfolio record, OpenSpec artifacts, and the generated board. An isolated native test lane owns regression tests under `ripdpi-tunnel-core` and `ripdpi-flow-app-attribution` until handoff. An isolated Android harness lane owns SO_BIND instrumentation, physical runner, evidence validation, and their tests. Cargo lockfiles, dependency versions, wire/schema production contracts, locale sets, and golden fixtures are serialized to the integration lane and are not planned to change. Read-only native and Android audits identified raw egress before admission, pending TCP attribution cleanup, and missing pre-5.7/socket-table acceptance paths. Historical physical results do not validate this new source revision; device acceptance remains open.
- 2026-08-27: After Android harness handoff, that writer owns only bounded UDP attribution-token metadata under `io_loop/udp_assoc/` in a new isolated worktree. The integration lane retains `routing.rs`, UDP dispatch/delivery admission ordering, the attribution crate, and TCP ownership; shared dispatch/delivery files are excluded from the UDP metadata lane.

- 2026-07-22: Assigned the remaining Android ICMP and MapDNS selector/evidence harness to its dedicated physical lane. Source enforcement is already shipped; this ownership record serializes the shared board while the device lane implements the exact physical actions and evidence contracts.
- 2026-07-22: **Shipped the MapDNS source admission boundary on this branch at implementation commit `f01763c4d6ac6c74657a615dded8240d0e3c2d8e`.** `IpClass::UdpDns` now preserves the exact kernel-visible synthetic destination tuple, parks unresolved datagrams in the bounded pending queue, admits only allowed UIDs before QNAME parsing or DNS-worker dispatch, avoids synthetic destination-attribution pollution, and leaves the disarmed path unchanged. The task remains `doing` only for the pre-5.7 device run, explicit `adb shell cat /proc/net/tcp` leak inspection, and physical ICMP/MapDNS DNS evidence; those checks are blocked by the current no-network/no-device permission. No device, VPN, DNS, route, Wi-Fi, or cellular state was changed in this lane.

- 2026-07-22: Reassigned the explicit ICMP policy boundary to the ICMP policy lane. This slice will add a default-deny ICMPv4/ICMPv6 decision when UID enforcement is armed, an explicit native-config opt-in for controlled callers, and Kotlin/Rust contract plus packet-routing regression tests. It will not start or stop RIPDPI VPN or alter MacBook/Pixel network state.

- 2026-07-22: Reassigned the remaining production eligibility gate to the Android eligibility lane. This slice replaces the API-level proxy with a cached, fail-closed capability decision: API 29+ plus either a successful unprivileged loopback `SO_BINDTODEVICE` probe, a parsed Linux kernel version at least 5.7, or the existing API 31+ fallback when the kernel release is unreadable. The physical network, Pixel, VPN state, DNS, routes, Wi-Fi, and cellular configuration are outside this lane.

- 2026-07-22: The local physical Pixel lane passed end to end on exact source SHA `2195272b78a08493adb09a7df90b270b6fafdefe` without hosted CI. A Pixel 7 (`panther`, API 37, kernel 6.1) and one dual-stack fixture exercised IPv4/IPv6 TCP+UDP direct controls, allowed `tun0` traffic, excluded-UID denied traffic, zero denied fixture delivery, TUN packet-path telemetry, and post-denial liveness. The strict v2 evidence validator accepted the private mode-0600 manifest; manifest SHA-256 `cb4e9c48c6bce79f3f072ae8b96cb936ab6b551529cdaa2c3ed105e5cb2278d2`. The task remains open only for the pre-5.7 version-gate run, explicit `/proc/net/tcp` leak inspection, and ICMP policy toggle.

- 2026-07-18: Hardened workflow finalization so a failed, cancelled, or skipped runtime step cannot preserve a stale `PASS`; missing runtime evidence is now classified as `TEST_FAILURE/RUNTIME_FAILED`, while canonical harness reasons such as `CLEANUP_FAILED` remain specific. The `so-bindtodevice-e2e` job in the manual privileged run passed on source SHA `05af20379ebb2a81b780d26efe9122ff5fb1882b`: [run 29652023020](https://github.com/po4yka/RIPDPI/actions/runs/29652023020), [job 88100347704](https://github.com/po4yka/RIPDPI/actions/runs/29652023020/job/88100347704). The downloaded canonical manifest validated all 12 IPv4/IPv6 TCP/UDP phases as `PASS`, reported unprivileged `SO_BINDTODEVICE` available, and verified cleanup; manifest SHA-256 `884c7ebeded84ea2cafe7898f066fc6a7d647307022e17466442e44f7dbf9625`. Android synthetic-app, kernel-version, `/proc/net/tcp`, and ICMP criteria remain open.

- 2026-07-17: The `so-bindtodevice-e2e` job in the corrected recurring privileged run passed on source SHA `e7e2f19d3358fe75b925d728859c291737fbf8aa`: [run 29541621476](https://github.com/po4yka/RIPDPI/actions/runs/29541621476), [job 87764872159](https://github.com/po4yka/RIPDPI/actions/runs/29541621476/job/87764872159). All 12 ordered IPv4/IPv6 TCP/UDP direct, allowed, and denied phases passed with a real `tun0`, unprivileged `SO_BINDTODEVICE`, packet-path/RST/zero-delivery proofs, and verified cleanup. The strict in-run validator accepted exact source/run provenance and the published `so-bindtodevice-tun-evidence` artifact (archive SHA-256 `ff720236699c1553968f8052a7407adc204bf4cae1e9112b9e522ae06f7993be`). The recurring physical Linux oracle is complete. The task remains `doing` for the Android synthetic-app run, kernel 5.7+/pre-5.7 device coverage, `adb /proc/net/tcp` leak check, and explicit ICMP policy.

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
