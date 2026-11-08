## Context

VPN-08 has an exact UID cache but joins app learning by destination IP. The Android callback writes a singleton cache without session ownership. TUN startup precedes worker registration.

## Goals / Non-Goals

- Goal: fix all five findings with bounded native and Kotlin state, without root.
- Non-goal: replace Android owner API, support ICMP UID lookup, activate physical probes, or change relay wire protocols.

## Decisions

- Apply an absolute five-second owner age to ordinary UDP as well as DNS admission. Preserve exact registration guards.
- Allocate an opaque random app scope for each resolved UID and package/version identity in a TUN session. Reuse it across TCP and UDP flows of that identity so fallback remains meaningful. Unknown/shared packages have no app scope.
- Return UID and optional scope through a kept callback result. Use a fresh callback object per TUN session with a captured epoch. Commit results under a common cache/session/package-revision lock after resolving outside it.
- Register attribution before native start. Roll back a returned registration token even when start was not attempted.
- Carry the scope through the password-authenticated loopback SOCKS username and into a separate optional learning-signal field. Scope participates in native learning keys, not just emitted signals. Existing diagnostics correlation remains separate.
- Resolve before constructing scoped SOCKS transports. Armed policy retains fail-closed admission. Disarmed traffic gets a bounded short grace (100 ms) and otherwise proceeds unscoped; an unscoped connection never borrows a later app identity. Use bounded UDP associations keyed by source and app scope so distinct known owners sharing a local endpoint can coexist. Retire obsolete exact-tuple response and error context before Pending or Denied admission. Keep an empty transport available during refresh: the same confirmed owner reuses its socket and NAT port; a different owner uses another keyed transport. Existing idle, capacity and stop cleanup bound empty transports.
- F2 and F5 share the scope implementation. F5 receives a separate regression commit for synthetic TCP/UDP; no duplicate IP alias implementation is added.
- Synchronize the mapping generation before acquiring a packet pin. A stale unleased mapping must remain rejected; existing association and queue pins protect active mappings through a reset. A mapped UDP packet then holds a temporary pin through retirement, admission and forwarding. A queued owner wait holds its own pin until retry, rejection or expiry. The forwarding association acquires its lease before packet pins are released. Empty transports do not keep orphan mapping pins.
- Optional disarmed attribution cannot discard supported UDP traffic when the wait queue has no room. Oversized reassembled packets and queue pressure bypass the grace with conservative identity. This does not enlarge buffers or relax armed admission.

## Contracts and ownership

- Integration owner: portfolio/OpenSpec artifacts, combined validation and integration only.
- UDP owner-age writer: ripdpi-flow-app-attribution/src/lib.rs and its expiry tests. Exclusive until its commit.
- Startup writer: core/engine Tun2SocksTunnel.kt and engine lifecycle tests only.
- Correlation/session writer: all remaining F3/F2/F5 changes, including FlowAppAttributionStore/Bridge, VpnTunnelRuntime, Kotlin telemetry models, native TUN/proxy/runtime-policy/ports/adapters and their tests. This writer owns serialized JNI files. Integration owns lower policy ports, telemetry adapters and the additive NativeRuntimeSnapshot contract; the startup writer takes proxy-runtime propagation after its startup commit. It starts from the UDP-age commit when touching the shared attribution crate. No concurrent writes to that crate.
- Cargo.lock, dependency catalog, proto files, locales, baselines and golden fixtures are not planned. Any required contract snapshot is regenerated through its owner, without weakening gates.
- After its UDP handoff, the UDP writer owns the F6 Lua host regression and minimal raw-hook ordering patch in its isolated worktree. The correlation writer retains pending-packet mapping leases and integrates F6 only after the F2 commit. No shared worktree writes are permitted.
- The correlation writer owns the F8 disarmed queue-budget repair and its real-wire regression on `fix/vpn08-attribution-grace`, based on the final integration code. Integration retains all Kotlin formatting and verification records.

## Risks / Trade-offs

- A slow resolver can make a disarmed flow unattributed; traffic must remain usable after the documented grace.
- UID lookup is platform evidence, not socket authentication. A finite cache leaves a finite reuse window.
- Old native callback objects may finish, but cannot commit into a new Kotlin epoch. No claim is made that Binder calls can be forcibly cancelled.
- Internal callback and telemetry contracts change in one combined tree; independent JNI and async review is required.

## Migration Plan

No persisted identity data exists. Build Kotlin and native artifacts together. Revert all contract commits together to roll back. Run targeted Rust and Kotlin suites, required independent reviews, staticAnalysis, just ci-gates, architecture health and locked metadata. Build affected Android native artifacts if environment permits. Physical two-UID/kernel acceptance remains explicitly unverified without separate authorization.
