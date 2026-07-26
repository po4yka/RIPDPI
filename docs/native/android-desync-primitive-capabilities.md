# Android Desync Primitive Capabilities — Non-Rooted Userspace Socket Path

**Scope:** non-rooted Android, proxy-mode path only — Rust holds a real OS socket fd (`TcpStream` / `UdpSocket`) and applies options or sequencing in userspace. The `ripdpi-root-helper` crate (raw sockets, `TCP_REPAIR`, `IP_HDRINCL`) is the root-only L3 path and is out of scope except where noted as a contrast.

**Audit date:** 2026-05-29. All file:line citations are relative to `native/rust/` unless otherwise noted.

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
| `fakedsplit` / `fakeddisorder` (ordered-segments, multi-disorder, seqovl) | **no-op** (needs-device) | `setsockopt(IPPROTO_TCP, TCP_REPAIR=19, 1)` + `socket(AF_INET, SOCK_RAW, IPPROTO_RAW)` | `CAP_NET_ADMIN` for `TCP_REPAIR`; `CAP_NET_RAW` for raw socket — both denied on unprivileged Android UID | Source audit + host tests `tcp_repair_setsockopt_fails_without_cap_net_admin`, `raw_socket_creation_fails_without_cap_net_raw`; Android OEM kernels need device |

> **Correction of a common misconception:** `fakedsplit` / `fakeddisorder` are **not** userspace stream-level approximations. They craft real L3/L4 packets via `SOCK_RAW` + `IP_HDRINCL` and freeze kernel send state via `TCP_REPAIR`. Both require capabilities an unprivileged Android app UID does not hold, so on non-rooted Android they degrade to a plain (weaker) split/write before any crafted packet is emitted.

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

## Primitive 3 — `fakedsplit` / `fakeddisorder` (ordered-segments, multi-disorder, seqovl)

### What they are

These primitives craft real L3/L4 TCP segments via raw sockets and inject them with custom sequence numbers and TTLs. They are not userspace stream approximations — they require kernel-level socket repair state and raw packet injection.

- **fakedsplit** (`send_ordered_tcp_segments`): uses `TCP_REPAIR` to freeze the kernel send queue, snapshots the current sequence number (`snd_nxt`), builds N crafted TCP segments via `SOCK_RAW`+`IP_HDRINCL`+`sendto`, then installs a replacement socket to advance the kernel send pointer to the correct post-payload position.
- **fakeddisorder** (`send_multi_disorder_tcp`): same `TCP_REPAIR` snapshot, builds ≥3 crafted segments covering the full payload, sends them in **reverse order** (`packets.iter().rev()` at `fragmentation.rs:173`), then calls `write_all(payload)` to send the real in-order payload.
- **seqovl** (`send_seqovl_tcp`): builds one raw packet with `seq = snd_nxt - fake_prefix.len()` — the overlap means the server TCP stack discards the overlapping bytes (already-ACKed range), while a DPI device that sees only the start of the segment sees fake content.

### Syscalls required

```
setsockopt(fd, IPPROTO_TCP, TCP_REPAIR=19, &1, 4)      — freeze kernel send state
getsockopt(fd, IPPROTO_TCP, TCP_REPAIR_QUEUE=20, ...)  — snapshot sequence number / ACK / window
socket(AF_INET, SOCK_RAW, IPPROTO_RAW)                 — raw IPv4 send socket
setsockopt(raw_fd, IPPROTO_IP, IP_HDRINCL, &1, 4)      — caller supplies full IP header
sendto(raw_fd, crafted_packet, len, 0, &sockaddr, addrlen)
setsockopt(fd, IPPROTO_TCP, TCP_REPAIR, &0, 4)         — disable TCP_REPAIR after snapshot
write(stream_fd, payload, len)                         — real payload (multi_disorder / seqovl paths)
```

### Call sites

- `crates/ripdpi-privileged-ops/src/linux/raw_packet/tcp_payload.rs:63` — `send_ordered_tcp_segments`: `set_tcp_repair(fd, TCP_REPAIR_ON)?` at line 83
- `crates/ripdpi-privileged-ops/src/linux/fragmentation.rs:133` — `send_multi_disorder_tcp`: `set_tcp_repair(fd, TCP_REPAIR_ON)?` at line 159; reverse-order send at line 173
- `crates/ripdpi-privileged-ops/src/linux/raw_packet/raw_socket.rs:39` — `Socket::new(Domain::IPV4, Type::RAW, Some(Protocol::from(libc::IPPROTO_RAW)))?`
- `crates/ripdpi-privileged-ops/src/linux/tcp_repair/sockopt.rs:53` — `set_tcp_repair` implementation
- `crates/ripdpi-privileged-ops/src/linux/raw_packet/tcp_payload.rs:185` — `send_seqovl_tcp`: same `TCP_REPAIR_ON` pattern

### Capability gap

Two distinct capabilities are required and both are denied for unprivileged UIDs on stock Android:

1. **`CAP_NET_ADMIN`** — required by the Linux kernel for `setsockopt(IPPROTO_TCP, TCP_REPAIR, 1)`. Denied for unprivileged app UIDs on stock AOSP.
2. **`CAP_NET_RAW`** — required to open `socket(AF_INET, SOCK_RAW, IPPROTO_RAW)`. Android's `INTERNET` permission grants only `SOCK_DGRAM`/`SOCK_STREAM`; `SOCK_RAW` with `IPPROTO_RAW` is denied for unprivileged UIDs.

The `RuntimeCapability` enum (`crates/ripdpi-capabilities/src/lib.rs:7-8`) names `RawTcpFakeSend` and `ReplacementSocket` as runtime-probed capabilities, but there is no automatic pre-gate that prevents the strategy from being attempted — the EPERM is encountered at the first actual syscall.

### Capability probe

`seqovl_supported()` at `crates/ripdpi-runtime-platform/src/retransmit.rs:22-26` (also exposed via `crates/ripdpi-proxy-runtime-desync-adapter/src/desync_platform/capability.rs:36-38`) calls `probe_tcp_repair()` at `crates/ripdpi-privileged-ops/src/linux/tcp_repair.rs:29`. If `setsockopt(TCP_REPAIR)` returns `EPERM`, the probe returns `false` and `seqovl_supported()` memoizes `false`.

However, `supports_fake_retransmit()` (`retransmit.rs:13-18`) is a **compile-time const** returning `true` for `linux|android` targets. This means `requires_special_tcp_execution` always returns `true` on Android, so `execute_tcp_plan` is always attempted. The EPERM only surfaces at the actual `set_tcp_repair` call — not before.

Additionally, the `ActivationContext.seqovl_supported` field (`crates/ripdpi-desync/src/types.rs:79`) is **written** from the probe result at `desync_platform.rs:200` but is **not read** in any production code path inside `ripdpi-desync` — production reads use the `seqovl_supported()` function probe directly, not the struct field (verified: `.seqovl_supported` field-read sites in non-test production code are zero; the only `.seqovl_supported()` reads are the trait method, a different symbol). The probe gates `WriteSeqOverlap` actions in the planner for `SeqOverlap` steps, but does not gate `fakedsplit`/`fakeddisorder` execution.

### Error handling

All errors are propagated, not swallowed:

- `set_tcp_repair(fd, TCP_REPAIR_ON)?` at `tcp_payload.rs:83` and `fragmentation.rs:159` — `?` immediately aborts on `EPERM`.
- `Socket::new(SOCK_RAW)` failure at `raw_socket.rs:40` — propagated via `?`.
- All `io::Error`s are lifted to `OutboundSendError::StrategyExecution { source, source_errno, fallback, ... }` by `strategy_result()` at `errors.rs:26-34`.
- The `fallback` field encodes the degraded strategy name (e.g., `split` for `seqovl`, `tlsrec_split` for `tlsrec_seqovl`).
- Cleanup `let _ = disable_tcp_repair(...)` calls at `tcp_payload.rs:127-128` and `fragmentation.rs:183-185` are intentional cleanup-path discards only, executed after the primary result is already determined.

The connection falls back to the degraded strategy (plain `split` / `disorder` without crafted raw packets) — a qualitatively different, weaker DPI bypass technique.

### Wire behavior

On non-rooted Android without a registered root helper: the very first `set_tcp_repair(TCP_REPAIR_ON)` call returns `EPERM`. No raw packets are crafted. No crafted TCP segments with discriminating TTLs or custom sequence numbers hit the wire. The strategy falls back. The intended mechanism — reversed raw segments, seqovl overlap, TTL-discriminated fakedsplit — is never executed.

On the reverse-order disorder mechanism specifically: `packets.iter().rev()` at `fragmentation.rs:173` is a pure Rust iterator operation that is correct in isolation (verified by test `multi_disorder_segments_are_emitted_in_reverse_order`), but it is never reached on a non-rooted device because the `TCP_REPAIR` prerequisite fails first.

The root-helper dispatch (`root_helper_dispatch.rs`) is the only escape hatch. It is only active on rooted devices with a registered privileged helper process.

### Verdict

**no-op** on non-rooted stock Android (needs-device to confirm OEM kernel/SELinux edge cases).

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

**Impact:** If the setsockopt succeeds, fake-TTL segments do land on the wire with the intended short TTL and the primitive is effective. If denied, the silent-swallow gate at `tcp_lowering.rs:67` means the payload is sent without TTL modification — genuine no-op for that code path.

---

### Item 3 — `TCP_REPAIR` / `SOCK_RAW`: OEM kernel/debug build exceptions

**Claim:** `setsockopt(IPPROTO_TCP, TCP_REPAIR, 1)` and `socket(AF_INET, SOCK_RAW, IPPROTO_RAW)` both return `EPERM` for an unprivileged UID on stock Android, making `fakedsplit` / `fakeddisorder` / `seqovl` no-ops.

**What source analysis says:** Stock AOSP kernels require `CAP_NET_ADMIN` for `TCP_REPAIR` and `CAP_NET_RAW` for raw sockets. The capability probe at `retransmit.rs:22-26` (`seqovl_supported()`) would return `false` on any kernel that denies `TCP_REPAIR`.

**What cannot be confirmed host-side:** Whether any OEM kernel, debug build, or Android-specific SELinux policy exception relaxes `CAP_NET_ADMIN`/`CAP_NET_RAW` for a VPN UID. Some custom kernels (e.g., certain Qualcomm-specific builds) are known to grant additional INET capabilities.

**Runtime check 1 — TCP\_REPAIR:** `setsockopt(IPPROTO_TCP, 19 /* TCP_REPAIR */, 1)` on a loopback TCP socket as a VPN UID. Assert `errno == EPERM`.

**Runtime check 2 — SOCK\_RAW:** `socket(AF_INET, SOCK_RAW, IPPROTO_RAW)` as a VPN UID. Assert returned fd is negative with `errno == EPERM` or `EACCES`.

**Impact:** If either syscall succeeds on a given device, `fakedsplit`/`fakeddisorder` would be effective on that device. The `seqovl_supported()` probe would automatically detect this and allow the strategy to be selected.

---

## Out of Scope

`ripdpi-root-helper` crate: `FakeRst`, `MultiDisorder` via raw sockets, `IpFrag2`, `fake_tcp_handlers`, `udp_fragment_handlers`, `tcp_fragment_handlers` — these operate on the root-only L3 raw-packet path. They are effective when a privileged helper process is registered but are inaccessible on non-rooted Android. They are not described here.

---

## Audit method

Generated by a multi-agent workflow (`android-desync-primitive-audit`, 2026-05-29): three independent readers (one per primitive) located every `setsockopt`/`send` call site and its error handling; a dedicated agent authored and ran host-side instrumentation tests (`crates/ripdpi-proxy-runtime-desync-adapter/tests/desync_primitive_audit_probe.rs`, 10 tests, all passing on host); three adversarial verifiers re-read the cited code independently and attempted to refute each verdict. The original framing hypothesis — that `fakedsplit`/`fakeddisorder` are userspace stream-level approximations — was refuted during verification and corrected above. Every runtime claim that depends on Android kernel/SELinux policy for an unprivileged UID is flagged needs-device rather than asserted from host behavior.
