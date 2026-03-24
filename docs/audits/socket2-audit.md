# socket2 Migration Audit

Audited: 2026-03-24 against socket2 0.5.10.

## Summary

socket2 is already used for every call site where it provides value. The
remaining raw `libc` calls use Linux kernel-specific features outside socket2's
abstraction scope.

## Raw Syscall Inventory

Primary file: `native/rust/crates/ripdpi-runtime/src/platform/linux.rs`

| Function | Raw Syscall | socket2 Equivalent | Complexity | Status |
|----------|-------------|---------------------|------------|--------|
| `enable_tcp_fastopen_connect` | `setsockopt(TCP_FASTOPEN_CONNECT)` | None | -- | Retain raw |
| `set_tcp_md5sig` | `setsockopt(TCP_MD5SIG)` | None | -- | Retain raw |
| `protect_socket` | `nix::sendmsg(SCM_RIGHTS)` | None | -- | Retain raw |
| `original_dst` | `getsockopt(SO_ORIGINAL_DST)` | None | -- | Retain raw |
| `attach_drop_sack` | `setsockopt(SO_ATTACH_FILTER)` | None | -- | Retain raw |
| `detach_drop_sack` | `setsockopt(SO_DETACH_FILTER)` | None | -- | Retain raw |
| `enable_recv_ttl` | `setsockopt(IP_RECVTTL)` | None | -- | Retain raw |
| `read_chunk_with_ttl` | `recvmsg` + CMSG | None | -- | Retain raw |
| `send_fake_tcp` | `vmsplice` + `splice` | None | -- | Retain raw |
| `peer_addr` | `getpeername` | `SockRef::peer_addr()` (wrong type) | -- | Retain raw |
| `read_tcp_info` | `getsockopt(TCP_INFO)` | None | -- | Retain raw |
| `set_stream_ttl` | `SockRef::set_ttl()` | **Already migrated** | trivial | Done |

Secondary file: `native/rust/crates/ripdpi-tun-driver/src/linux.rs` -- 7 `ioctl`
calls for TUN device configuration. Out of socket2's scope.

## Already Using socket2

| Location | API |
|----------|-----|
| `runtime/listeners.rs` | `Socket::new()`, `set_reuse_address()`, `bind()`, `listen()` |
| `runtime/udp.rs` | `Socket::new()`, `set_reuse_address()` |
| `platform/linux.rs` | `SockRef::set_ttl()`, `set_unicast_hops_v6()` |
| `runtime/handshake.rs` | `SockRef::set_linger()` |
| `ws-tunnel/connect.rs` | `Socket::new()` |

## Unsupported by socket2 (Must Retain Raw libc)

1. **TCP_FASTOPEN_CONNECT** -- client-side TFO (not in 0.5 or 0.6)
2. **TCP_INFO** -- custom 41-field kernel struct for adaptive markers
3. **TCP_MD5SIG** -- authentication struct for spoofed packets
4. **SO_ATTACH_FILTER / SO_DETACH_FILTER** -- BPF socket filter
5. **SO_ORIGINAL_DST / IP6T_SO_ORIGINAL_DST** -- iptables transparent proxy
6. **IP_RECVTTL / IPV6_RECVHOPLIMIT** -- TTL in ancillary data
7. **recvmsg with CMSG** -- ancillary data iteration
8. **vmsplice / splice** -- zero-copy kernel pipe operations

## Platform Gating

All Linux-specific functions are gated in `platform/mod.rs` with
`#[cfg(any(target_os = "linux", target_os = "android"))]`. Non-Linux targets
return `Err(Unsupported)`.

## Improvements Made

Extracted `setsockopt_raw<T>` and `getsockopt_raw<T>` generic helpers to
centralize the unsafe boilerplate. Reduced 10 repetitive unsafe blocks to
one-liner calls through 2 well-audited functions.

## Future Considerations

- Monitor socket2 releases for `TCP_FASTOPEN_CONNECT` support
- Consider upgrading `socket2 = "0.5"` to `"0.6"` to deduplicate with the
  0.6.3 pulled transitively by tokio/hyper (separate PR, breaking API changes)
