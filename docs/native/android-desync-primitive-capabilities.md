# Android Desync Primitive Capabilities — Non-Rooted Userspace Socket Path

**Scope:** non-rooted Android, proxy-mode path only — Rust holds a real OS socket fd (`TcpStream` / `UdpSocket`) and applies options or sequencing in userspace. The `ripdpi-root-helper` crate (raw sockets, `TCP_REPAIR`, `IP_HDRINCL`) is the root-only L3 path and is out of scope except where noted as a contrast.

**Audit date:** 2026-05-29. Primitive 3 and its summary were corrected on 2026-11-12. Other detailed sections remain historical. All source paths are relative to `native/rust/` unless otherwise noted.

> **Current-state correction (2026-07-26).** The detailed audit below is a
> historical snapshot and several runtime conclusions have since changed.
> Android settings enable `md5sig` only when root mode is enabled
> (`NativeProxyDesyncPreferencesMapper`); unsupported/permission errors now
> degrade gracefully and preserve the real payload instead of failing the
> connection. Raw-packet operations dispatch to a registered root helper first,
> then use the local platform path where permitted. Startup capability discovery
> uses `probe_ip_fragmentation_capabilities`, so the helper is not the only
> capability signal. Use the current mapper, `PrivilegedActionExecutor`, and
> runtime-platform capability probe as the source of truth rather than the
> obsolete unconditional-attempt and “only escape hatch” statements below.

---

## Summary Table

| Primitive | Verdict | Syscall | Capability gap | Confirmed by |
|---|---|---|---|---|
| `TCP_MD5SIG` (`DesyncAction::SetMd5Sig`) | **no-op** (needs-device) | `setsockopt(IPPROTO_TCP, TCP_MD5SIG, ...)` | `CAP_NET_ADMIN` — no variant in `RuntimeCapability`, no pre-probe | Source audit + host test `md5sig_setsockopt_is_not_silently_swallowed`; Android kernel policy needs device |
| IP\_TTL fake-TTL via `setsockopt` on `TcpStream` | **partial** (needs-device) | `setsockopt(IPPROTO_IP, IP_TTL, ttl)` / `setsockopt(IPPROTO_IPV6, IPV6_UNICAST_HOPS, ttl)` | None on desktop Linux; Android SELinux/kernel policy may deny — explicit EPERM swallow gate at `tcp_lowering.rs:102` | Source audit + host test `ip_ttl_setsockopt_is_applied_on_connected_stream`; Android kernel/SELinux needs device |
| `fakedsplit` / `fakeddisorder` default transport | **platform-dependent approximation** | TTL options, `vmsplice`, and `splice` on Linux/Android | No raw capability is required for the default stream path; TTL and kernel behavior still require validation | Source path traced; syscall probes alone do not prove payload delivery or DPI effect |
| Fake-family ordered raw transport | **requires raw capabilities** | `TCP_REPAIR` and `SOCK_RAW` | Custom order or sequential mode selects the ordered sender; raw permissions or a registered root helper are required | Source path traced; repair/raw socket probes test separate prerequisites, not the complete sender |

> The default fake-family transport and the ordered raw transport are separate paths. `fakeddisorder` is not `MultiDisorder`. A successful syscall probe does not establish packet order, real-payload delivery, or DPI effectiveness. See Primitive 3 for the current boundaries.

---

## Primitive 1 — `TCP_MD5SIG` (`DesyncAction::SetMd5Sig`)

### What it is

`TCP_MD5SIG` installs an MD5 authentication key on a socket. When active, the kernel appends an MD5 TCP option to every outgoing segment. As a desync primitive the intent is for fake/injected segments to carry a valid MD5 signature while real segments do not (or vice versa), confusing stateful DPI reassembly.

### Call chain on the non-root proxy path

```
DesyncAction::SetMd5Sig { key_len }
  → tcp_actions.rs:98  PrivilegedActionExecutor::set_md5sig(writer, *key_len, &context, &accounting)?
  → strategy_actions.rs:239-250  raw_socket::set_tcp_md5sig(stream, key_len)
      wrapped via strategy_result() → OutboundSendError::StrategyExecution
  → crates/ripdpi-privileged-ops/src/linux/socket_options.rs:128-139
      unsafe { setsockopt_raw(fd, libc::IPPROTO_TCP, libc::TCP_MD5SIG, &md5) }
```

Dispatch goes through `RuntimeTcpDesyncPlatform::set_tcp_md5sig` at `crates/ripdpi-proxy-runtime-desync-adapter/src/desync_platform/socket_options.rs:11-25` — there is no root-helper dispatch on this call path; it is a direct `setsockopt` on the live stream fd (confirmed: `peer_addr(fd)` then `setsockopt_raw` on `stream.as_raw_fd()`).

### Syscall

```
setsockopt(fd, IPPROTO_TCP, TCP_MD5SIG, &tcp_md5sig_struct, sizeof(tcp_md5sig_struct))
```

The `#[cfg(any(target_os = "linux", target_os = "android"))]` gate at `crates/ripdpi-privileged-ops/src/linux/socket_options.rs:23` ensures this compiles in for Android targets.

### Capability gap

`CAP_NET_ADMIN` is required by the Linux kernel (`net/ipv4/tcp.c`) for `TCP_MD5SIG`. There is no capability pre-probe in the RIPDPI codebase for this option:

- `RuntimeCapability` enum (`crates/ripdpi-capabilities/src/lib.rs:4-15`) has no `Md5Sig` variant. The full enum is: `TtlWrite`, `RawTcpFakeSend`, `RawUdpFragmentation`, `ReplacementSocket`, `RootHelperAvailable`, `VpnProtect`, `VpnProtectCallback`, `VpnMode`, `TcpWindowClamp`, `NetworkBinding`.
- No `CapabilityOutcome` probe analogous to `try_set_stream_ttl_with_outcome` exists for `TCP_MD5SIG` anywhere in the codebase.

The setsockopt is therefore attempted unconditionally whenever `group.actions.md5sig` is true.

### Error handling

The primary set call (activate, `key_len=5`) at `fake_tcp.rs:83` uses `?` and propagates. `strategy_result()` at `strategy_actions.rs:250` wraps the error into `OutboundSendError::StrategyExecution { action, strategy_family, fallback, bytes_committed, source_errno }`. This is **not swallowed**; an `EPERM` from the kernel reaches the `send_tcp_desync_payload` caller at `desync_platform.rs:162` as an error.

The single `let _ = set_tcp_md5sig(stream, 0)` at `fake_tcp.rs:117` is an intentional cleanup discard on the error path, executed only after the outer closure has already produced its result. This is not a silent swallow of the primary activation failure.

Unlike the TTL path (see Primitive 2), there is **no** `should_ignore_android_ttl_error`-style silent-swallow gate for `TCP_MD5SIG` on Android. An `EPERM` propagates as `OutboundSendError`, which may cause connection failure rather than graceful fallback on affected devices. This asymmetry with the TTL path is worth noting for reliability triage.

### UDP path

`udp_desync.rs:145`:
```rust
DesyncAction::SetMd5Sig { .. } => Ok(()),  // explicit no-op; setsockopt never called
```

### Wire behavior

On the non-rooted Android userspace proxy path: the `setsockopt` call is attempted, the kernel returns `EPERM` (unprivileged UID lacks `CAP_NET_ADMIN`), and the error propagates — no wire behavior change occurs. Nothing hits the wire differently.

### Verdict

**no-op** on stock Android (needs-device to confirm kernel/SELinux policy).

---

## Primitive 2 — IP\_TTL fake-TTL via `setsockopt` on `TcpStream`

### What it is

The stream-write path applies `setsockopt(IP_TTL, fake_ttl)` to the live `TcpStream` fd before splicing fake prefix bytes, causing those bytes to leave the NIC with a short TTL. The TTL is then restored to the normal value before the real payload is sent. The design goal is that fake bytes expire at an intermediate router before reaching the server, while the real bytes survive.

### Syscalls

```
setsockopt(fd, IPPROTO_IP, IP_TTL, &ttl, sizeof(ttl))
setsockopt(fd, IPPROTO_IPV6, IPV6_UNICAST_HOPS, &ttl, sizeof(ttl))   // IPv6 path
```

### Call sites

Stream-write path (in scope for this document):

- `crates/ripdpi-privileged-ops/src/linux/raw_packet/fake_tcp.rs:62` — `try_set_stream_ttl_with_outcome(stream, ttl)` called before splice
- `crates/ripdpi-privileged-ops/src/linux/socket_options.rs:297-300` — `set_stream_ttl` calls `socket2::SockRef::set_ttl_v4(ttl)` / `set_unicast_hops_v6(ttl)`
- `crates/ripdpi-privileged-ops/src/linux/socket_options.rs:321-329` — `try_set_stream_ttl_with_outcome` maps `EACCES | EPERM` → `CapabilityOutcome::Unavailable { capability: RuntimeCapability::TtlWrite, reason: CapabilityUnavailable::PermissionDenied }`

DesyncAction path (tcp_lowering, separate from fake_tcp):

- `crates/ripdpi-desync-runtime/src/tcp_lowering.rs:65` — `set_stream_ttl(stream, ttl)` called directly

### Capability gap

None on standard desktop Linux — `IP_TTL` setsockopt does not require `CAP_NET_ADMIN` for values 1–255 for any unprivileged UID. The code itself acknowledges that some Android kernels or SELinux profiles may deny it: `CapabilityUnavailable::PermissionDenied` is a defined outcome at `socket_options.rs:329`.

### Error handling — two separate code paths

**Stream-write (fake_tcp.rs) path:** `try_set_stream_ttl_with_outcome` returns a typed `CapabilityOutcome`. When `Unavailable { reason: PermissionDenied }` or `Unsupported` is returned, `fake_tcp.rs:66-77` returns `Err(io::Error::from_raw_os_error(...))`. The caller (`crates/ripdpi-runtime-platform/src/fake_send/fake_tcp.rs:44`) checks `should_fallback_raw_fake_tcp`, which matches `PermissionDenied` and `Unsupported` — this triggers `send_fake_tcp_via_raw_packets` (the raw socket path), which itself requires `SOCK_RAW`+`TCP_REPAIR` and will also fail with `EPERM` on a non-rooted UID. The error is propagated, not silently swallowed on this path.

**DesyncAction / tcp\_lowering path:** `crates/ripdpi-desync-runtime/src/tcp_lowering.rs:102-107`:
```rust
#[cfg(any(test, target_os = "android"))]
pub(crate) fn should_ignore_android_ttl_error(err: &io::Error) -> bool {
    matches!(
        err.raw_os_error(),
        Some(libc::EROFS | libc::EINVAL | libc::ENOPROTOOPT
            | libc::EOPNOTSUPP | libc::EPERM | libc::EACCES)
    )
}
```
When the setsockopt fails with any of these errnos, `ttl_write` is set to `TcpTtlCapabilityState::Unavailable` and the error is **silently swallowed** — the payload is still sent, but without TTL modification. Subsequent connections in the same session skip TTL actions entirely via the `ttl_actions_unavailable()` check. This is a genuine silent degradation to a no-op for the TTL primitive on the DesyncAction code path.

Restore is double-guarded: inside the closure at `fake_tcp.rs:112` (`set_stream_ttl(stream, restore_ttl)?`) and a safety-net at `fake_tcp.rs:119` (`let _ = set_stream_ttl(stream, restore_ttl)`). `tcp_actions.rs:170` has an additional safety-net if `ttl_modified` is true on early exit.

### TTL value selection

If `group.actions.auto_ttl` is set, `AdaptiveFakeTtlResolver::resolve()` (`crates/ripdpi-runtime-adaptive/src/adaptive_fake_ttl.rs:33`) computes the fake TTL anchored to the detected DPI hop distance. The seed comes from `detected_from_observed_ttl` at `adaptive_fake_ttl.rs:113`:
```rust
let reference: u8 = if observed <= 64 { 64 } else if observed <= 128 { 128 } else { 255 };
let hops = reference.saturating_sub(observed);
hops.saturating_sub(1).max(1)   // one less than measured hop count
```
This is refined per-connection by success/failure feedback. If `auto_ttl` is not set, the static `group.actions.ttl` value is used. The observed TTL is read via `getsockopt(IPPROTO_IP, IP_TTL)` at `crates/ripdpi-diagnostics-transport/src/platform_ttl.rs:11`.

### Wire behavior

When `setsockopt(IP_TTL)` is accepted by the kernel: TCP segments flushed during the splice window carry the fake TTL value; the socket TTL is restored immediately after, so all subsequent real bytes carry the normal TTL. The fake TTL therefore affects only the fake prefix bytes.

When the kernel rejects it (EPERM on Android):
- On the fake_tcp stream-write path: error propagates; the fallback to raw packets is also blocked (needs `SOCK_RAW`); no fake TTL packet is emitted.
- On the DesyncAction tcp_lowering path: error is silently swallowed on Android; payload is sent without TTL modification.

### Verdict

**partial** — effective on kernels that permit `IP_TTL` setsockopt for unprivileged VPN UIDs; silently degrades to no-op on kernels/SELinux profiles that return `EPERM`/`EACCES`. Whether stock Android 10–15 permits this for a VPN-service UID cannot be determined from source alone.

---

## Primitive 3 — Fake-family stream and raw transports

### Execution owners

`crates/ripdpi-desync-runtime/src/tcp_plan/execution/dispatcher.rs` routes `FakeSplit`
and `FakeDisorder` to the fake-family executor. The `FakeDisorder` executor is
`crates/ripdpi-desync-runtime/src/tcp_fake_family/fake_disorder.rs`.
`MultiDisorder` uses a separate executor. It must not be used as evidence for
`fakeddisorder` behavior.

| Selected path | Implementation | Permission and behavior boundary |
|---|---|---|
| Default order and duplicate sequence mode, without raw-only options | `crates/ripdpi-privileged-ops/src/linux/raw_packet/fake_tcp.rs::send_fake_tcp` | The Linux/Android stream path uses an anonymous mapping, `vmsplice`, `splice`, TTL changes, and replacement of queued fake bytes with real bytes. It does not require `TCP_REPAIR` or `SOCK_RAW`. |
| Secondary fake or timestamp modifier | `send_fake_tcp_via_raw_packets` in the same file | These configured modifiers select raw transport. If the sender cannot apply them, it returns the raw-path error instead of silently dropping the selected feature and using the stream path. |
| Fake/original flag overrides or an explicit raw requirement | `send_fake_tcp_via_raw_packets` | These options require the exact raw path. Its errors are returned instead of silently replacing the requested flags with an ordinary write. |
| Non-default fake order or sequential sequence mode | `crates/ripdpi-desync-runtime/src/tcp_fake_family/options.rs`, then `crates/ripdpi-privileged-ops/src/linux/raw_packet/tcp_payload.rs::send_ordered_tcp_segments` | The ordered sender uses `TCP_REPAIR` and `SOCK_RAW`. Genuine bytes remain in the TCP send queue for retransmission. Failure to enter repair mode returns an error before the batch is sent. |
| Terminal split boundary | `fake_disorder.rs::execute_boundary_fallback` | No second region exists, so this path sends no fake. It uses a TTL-sensitive ordinary write and wait. Its receipt must describe the lowered operation. |

For `fakeddisorder`, TTL 1 initially suppresses the first real region on a routed
path. The second region uses the configured fake transport. This is the
Linux/Android approximation described by the app; it is not a promise of the
same packet sequence as upstream zapret. A local endpoint can receive a TTL-1
fake, so a successful loopback TLS exchange is not a suitable fake-expiry test.

### Runtime dispatch and capability gates

`crates/ripdpi-runtime-platform/src/fake_send/fake_tcp.rs` first uses a reachable
registered root helper, then uses the local privileged-operations implementation
when no helper is selected. Root mode is opt-in. A helper is one route to raw
permissions, not the only possible runtime capability signal.

`supports_fake_retransmit()` in `crates/ripdpi-runtime-platform/src/retransmit.rs` is a
compile-time Linux/Android flag. It is not a probe of raw permissions. It causes
fake-family plans to use their specialized executor on those platforms.
`seqovl_supported()` probes TCP repair capability for the sequence-overlap path;
it does not disable every `fakedsplit` or `fakeddisorder` configuration.

Raw paths require both TCP repair and raw-socket access. Their availability
must be checked in the actual app/helper context. Failure of one raw primitive
does not establish that the default stream path is unavailable. An error's
fallback label is diagnostic metadata; it does not itself execute that fallback.

### What host probes establish

The host tests in
`crates/ripdpi-proxy-runtime-desync-adapter/tests/desync_primitive_audit_probe.rs`
check individual socket operations and small in-memory layouts. In particular:

- `tcp_repair_setsockopt_fails_without_cap_net_admin` observes one repair request.
- `raw_socket_creation_fails_without_cap_net_raw` observes one raw socket request.
- `multi_disorder_segments_are_emitted_in_reverse_order` checks a local vector
  reversal. It does not call the `fakeddisorder` executor or send packets.
- The ordered-segment TTL test checks supplied segment values. It does not
  establish the emitted header bytes or the selected runtime path.

Passing these probes does not prove actual fake bytes, TTL or Hop Limit on the
wire, sequence order, retransmission content, complete payload delivery, or DPI
effectiveness. Those claims require an independent packet capture and a peer
that checks the original application bytes. Android kernel policy, root-helper
routing, error cleanup, and cancellation require their own runtime evidence.

### Verdict

The default Linux/Android fake-family path is a stream approximation. Ordered
and exact raw variants have additional permission requirements. There is no
single non-root no-op verdict for all `fakedsplit` and `fakeddisorder` variants.
Device-specific behavior and external DPI compatibility remain unverified by
these host probes.

---

## Needs Device / CI Runner to Confirm

The following claims cannot be settled from source code analysis on a developer host. Each requires a physical Android device or an Android CI runner (e.g., Firebase Test Lab with a stock Pixel, Android 14 or 15).

### Item 1 — `TCP_MD5SIG`: kernel denial confirmation

**Claim:** `setsockopt(IPPROTO_TCP, TCP_MD5SIG, ...)` returns `EPERM` for an unprivileged VPN UID on stock Android.

**What source analysis says:** The Linux kernel `net/ipv4/tcp.c` checks `capable(CAP_NET_ADMIN)`. A standard app UID does not hold `CAP_NET_ADMIN`. EPERM is the expected result.

**What cannot be confirmed host-side:** Whether Android vendor kernels or SELinux policies substitute `EACCES` for `EPERM`, or whether any SELinux policy exception exists for VPN UIDs. Some OEM kernels grant extended INET capabilities to VPN service UIDs.

**Runtime check:** `adb shell` as the app UID: `setsockopt()` on a connected TCP fd with `TCP_MD5SIG=14`. Assert `errno == EPERM` or `EACCES`.

**Impact:** If a vendor kernel permits the call, `TCP_MD5SIG` would take effect and MD5 authentication would be attached to outgoing segments. The no-op verdict would change to effective for that device.

---

### Item 2 — IP\_TTL: Android kernel/SELinux permission for unprivileged VPN UID

**Claim:** `setsockopt(IPPROTO_IP, IP_TTL, ttl)` may be permitted or denied depending on Android kernel/SELinux profile for an unprivileged VPN UID.

**What source analysis says:** Desktop Linux permits this for any unprivileged UID (values 1–255, no `CAP_NET_ADMIN` required). The code's explicit `#[cfg(target_os = "android")]` EPERM/EACCES silent-swallow gate at `tcp_lowering.rs:102-107` is the strongest evidence that at least some real Android devices deny it.

**What cannot be confirmed host-side:** Whether the denial is universal across Android 10–15, AOSP vs OEM kernels, and VPN-service UIDs vs regular app UIDs. The partial verdict depends on whether any real-world Android device in scope actually permits the setsockopt.

**Runtime check:** On target device: connect a TCP socket to a remote address, call `setsockopt(IPPROTO_IP, IP_TTL, 42)`, call `getsockopt(IPPROTO_IP, IP_TTL)` and assert readback == 42. Run as a VPN service UID (i.e., with `BIND_VPN_SERVICE` permission). Repeat on Android 12, 13, 14, and 15 with stock Pixel firmware and one OEM device.

**Impact:** successful readback establishes the socket option in the tested context. Packet capture must check emitted TTL or Hop Limit, and the peer must check the original bytes before a delivery or DPI claim is made. If the option is denied, inspect the selected executor and its receipt to establish the actual fallback.

---

### Item 3 — `TCP_REPAIR` / `SOCK_RAW`: OEM kernel/debug build exceptions

**Claim to check:** the selected raw path may lack TCP repair or raw-socket permission in the app UID. This concerns ordered/exact raw variants, not the default fake-family stream path.

**What source analysis says:** Stock AOSP kernels require `CAP_NET_ADMIN` for `TCP_REPAIR` and `CAP_NET_RAW` for raw sockets. The capability probe at `retransmit.rs:22-26` (`seqovl_supported()`) would return `false` on any kernel that denies `TCP_REPAIR`.

**What cannot be confirmed host-side:** Whether any OEM kernel, debug build, or Android-specific SELinux policy exception relaxes `CAP_NET_ADMIN`/`CAP_NET_RAW` for a VPN UID. Some custom kernels (e.g., certain Qualcomm-specific builds) are known to grant additional INET capabilities.

**Runtime check 1 — TCP\_REPAIR:** `setsockopt(IPPROTO_TCP, 19 /* TCP_REPAIR */, 1)` on a loopback TCP socket as a VPN UID. Assert `errno == EPERM`.

**Runtime check 2 — SOCK\_RAW:** `socket(AF_INET, SOCK_RAW, IPPROTO_RAW)` as a VPN UID. Assert returned fd is negative with `errno == EPERM` or `EACCES`.

**Impact:** success of one syscall establishes only that prerequisite in the tested context. Both raw prerequisites, the selected sender, packet bytes, and peer delivery must be checked before making a raw-path claim. The `seqovl_supported()` result does not select all fake-family variants, and neither probe proves DPI effectiveness.

---

## Out of Scope

`ripdpi-root-helper` operations, including `FakeRst`, `MultiDisorder`, `IpFrag2`, and raw fake-payload handlers, are outside this non-root app-UID audit. A registered helper can provide raw permissions; registration alone does not prove packet bytes, delivery, or external compatibility.

---

## Audit method

Generated by a multi-agent workflow (`android-desync-primitive-audit`, 2026-05-29): three independent readers (one per primitive) located every `setsockopt`/`send` call site and its error handling; a dedicated agent authored and ran host-side instrumentation tests (`crates/ripdpi-proxy-runtime-desync-adapter/tests/desync_primitive_audit_probe.rs`, 10 tests, all passing on host); three adversarial verifiers re-read the cited code independently and attempted to refute each verdict. The original report conflated the default stream path with the ordered raw path. The 2026-11-12 correction above replaces that conclusion with the current execution owners and evidence limits. Every runtime claim that depends on Android kernel/SELinux policy for an unprivileged UID is flagged needs-device rather than asserted from host behavior.
