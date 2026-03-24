# io_uring Feasibility Audit

Audited: 2026-03-24 against tokio-uring 0.5, io-uring 0.7, Linux io_uring ABI.

## Summary

io_uring is **not viable** for the RIPDPI proxy runtime on Android. Three
independent blockers, any one of which is sufficient:

1. **Android seccomp kills the process.** The zygote-installed BPF filter denies
   `io_uring_setup` (NR 425), `io_uring_enter` (NR 426), `io_uring_register`
   (NR 427) with SIGKILL on all shipping Android versions through Android 15.
2. **Desync syscalls cannot be expressed in io_uring.** `setsockopt`/`getsockopt`
   require `IORING_OP_SETSOCKOPT`/`GETSOCKOPT` (Linux 6.7+), absent from every
   Android kernel. Even with those ops, the sequential dependency chain
   (set TTL -> send fake -> poll TCP_INFO -> restore TTL) cannot be batched.
3. **The actual bottleneck has no io_uring solution.** `wait_tcp_stage_fd` polls
   `getsockopt(TCP_INFO)` in a busy loop with 1ms sleeps waiting for
   `tcpi_notsent_bytes == 0`. There is no io_uring notification for
   "TCP send queue drained."

This document records the analysis so the question need not be revisited
without new evidence.

## Current I/O Profile

Primary file: `native/rust/crates/ripdpi-runtime/src/platform/linux.rs`

### Connection Lifecycle Syscall Map

| Phase | File | Key Syscalls | Thread | Calls per Connection |
|-------|------|-------------|--------|---------------------|
| Accept | `runtime/listeners.rs:289-344` | `epoll_wait`, `accept4` | listener (mio poll) | 1 |
| Handshake | `runtime/handshake/protocol_io.rs` | `read_exact` (1-3x), `write_all` | worker | 2-5 |
| Target resolve | `runtime/handshake/connect_relay.rs` | `getsockopt(SO_ORIGINAL_DST)` or DNS | worker | 1-2 |
| Upstream connect | `runtime/handshake/connect_relay.rs` | `socket`, `setsockopt(TFO)`, `connect` | worker | 2-3 |
| First exchange | `runtime/relay/first_exchange.rs` | `recvmsg`+CMSG, `setsockopt(IP_RECVTTL)` | worker | 2-5 |
| Desync planning | `runtime/desync.rs:61-99` | `getsockopt(TCP_INFO)` | worker | 1 |
| Desync execution | `runtime/desync.rs:118-411` | `setsockopt(TTL)` x2, `setsockopt(TCP_MD5SIG)` x2, `vmsplice`, `splice`, `write` | worker | 8-20 |
| TCP stage wait | `platform/linux.rs:423-447` | `getsockopt(TCP_INFO)` in 1ms poll loop | worker | 5-5000 |
| Relay (inbound) | `runtime/relay/stream_copy.rs:71-99` | `read`, `write_all` | ripdpi-dn | 2/chunk |
| Relay (outbound) | `runtime/relay/stream_copy.rs:138-201` | `read`, `send_with_group` (may desync) | ripdpi-up | 2-10/chunk |
| Shutdown | `runtime/relay/stream_copy.rs:96-98` | `shutdown(Write)`, `shutdown(Read)` | up/dn | 2 |

**Total per connection:** 20-50 syscalls (no desync) to 100+ (heavy desync with retries).

### Threading Model

| Component | Source | Threads |
|-----------|--------|---------|
| Listener | `listeners.rs:245-351` | 1 (main, mio poll loop, 250ms timeout) |
| Worker pool | `listeners.rs:48-228` | Baseline `min(max_open, parallelism*2, 16)`, max `config.network.max_open` |
| Relay per conn | `stream_copy.rs:48-57` | 2 (ripdpi-dn + ripdpi-up) |
| UDP relay | `runtime/udp.rs` | 1 per UDP associate |

**Per active connection:** 3 threads (1 worker + 2 relay).
**At capacity (256 max_open):** ~768 threads. Worker idle timeout: 30s.

### Desync Fake Packet Syscall Chain (Critical Hot Path)

`send_fake_tcp` at `platform/linux.rs:251-326`:

```
1. set_stream_ttl(stream, fake_ttl)         -- setsockopt(IP_TTL)
2. set_tcp_md5sig(stream, 5)                -- setsockopt(TCP_MD5SIG)  [conditional]
3. vmsplice(pipe_w, &iov, SPLICE_F_GIFT)    -- queue fake bytes into pipe
4. splice(pipe_r, stream, len)              -- zero-copy send from pipe to socket
5. wait_tcp_stage_fd(fd, ...)               -- getsockopt(TCP_INFO) poll loop
6. set_tcp_md5sig(stream, 0)                -- setsockopt(TCP_MD5SIG)  [restore]
7. set_stream_ttl(stream, default_ttl)      -- setsockopt(IP_TTL)     [restore]
```

Each step depends on the previous: the TTL must be set *before* the splice
sends the segment; the TCP_INFO poll must complete *before* restoring the TTL
(otherwise the real retransmit goes out with the fake TTL). This chain is
**fundamentally sequential** -- io_uring SQE linking cannot help because each
completion result determines whether the next step proceeds.

## Android Kernel Version Matrix

| Android | API Level | Min Kernel | io_uring in Kernel | seccomp Blocks io_uring |
|---------|-----------|------------|-------------------|------------------------|
| 8.1 (minSdk) | 27 | 4.4 | No (needs 5.1+) | N/A |
| 10 | 29 | 4.14 | No | N/A |
| 11 | 30 | 4.19 | No | N/A |
| 12 | 31 | 5.10 | Present | **Yes** |
| 13 | 33 | 5.10/5.15 | Present | **Yes** |
| 14 | 34 | 5.15/6.1 | Present | **Yes** |
| 15 | 35 | 6.1/6.6 | Present | **Yes** |

Android's seccomp BPF policy (`SECCOMP_MODE_FILTER`), installed by zygote
before the app process starts, explicitly denies:
- `__NR_io_uring_setup` (425 on arm64)
- `__NR_io_uring_enter` (426)
- `__NR_io_uring_register` (427)

The process receives SIGKILL, not EPERM. This is not configurable per-app.
Google added this block in Android 12 when the kernel first shipped with
io_uring compiled in, citing the kernel attack surface. Google has publicly
stated this policy will not change for unprivileged apps.

**Runtime detection is not possible.** The process is killed before
`io_uring_setup()` returns, so there is no error code to check.

## io_uring Operation Coverage

Map of every `platform/linux.rs` syscall to its io_uring equivalent:

| Operation | Current Syscall | io_uring Op | Linux Version | Android Status |
|-----------|----------------|-------------|---------------|----------------|
| `set_stream_ttl` | `setsockopt(IP_TTL)` | `IORING_OP_SETSOCKOPT` | 6.7 | **No kernel ships this** |
| `set_tcp_md5sig` | `setsockopt(TCP_MD5SIG)` | `IORING_OP_SETSOCKOPT` | 6.7 | **No kernel ships this** |
| `enable_tcp_fastopen_connect` | `setsockopt(TCP_FASTOPEN_CONNECT)` | `IORING_OP_SETSOCKOPT` | 6.7 | **No kernel ships this** |
| `attach_drop_sack` | `setsockopt(SO_ATTACH_FILTER)` | None | Never | BPF attach is not an io_uring op |
| `detach_drop_sack` | `setsockopt(SO_DETACH_FILTER)` | None | Never | BPF detach is not an io_uring op |
| `enable_recv_ttl` | `setsockopt(IP_RECVTTL)` | `IORING_OP_SETSOCKOPT` | 6.7 | **No kernel ships this** |
| `read_chunk_with_ttl` | `recvmsg` + CMSG | `IORING_OP_RECVMSG` | 5.3 | Available (blocked by seccomp) |
| `send_fake_tcp` (vmsplice) | `vmsplice` | None | Never | Not an io_uring op |
| `send_fake_tcp` (splice) | `splice` | `IORING_OP_SPLICE` | 5.7 | Available (blocked by seccomp) |
| `read_tcp_info` | `getsockopt(TCP_INFO)` | `IORING_OP_GETSOCKOPT` | 6.7 | **No kernel ships this** |
| `original_dst` | `getsockopt(SO_ORIGINAL_DST)` | `IORING_OP_GETSOCKOPT` | 6.7 | **No kernel ships this** |
| `protect_socket` | `sendmsg(SCM_RIGHTS)` | `IORING_OP_SENDMSG` | 5.3 | Available (blocked by seccomp) |
| Relay read | `read` | `IORING_OP_READ` | 5.6 | Available (blocked by seccomp) |
| Relay write | `write` / `write_all` | `IORING_OP_WRITE` | 5.6 | Available (blocked by seccomp) |
| Accept | `accept4` (via mio) | `IORING_OP_ACCEPT` | 5.5 | Available (blocked by seccomp) |
| Connect | `connect` | `IORING_OP_CONNECT` | 5.5 | Available (blocked by seccomp) |

**Summary:** 7 of 17 operations have no io_uring equivalent at any kernel
version. Of the 10 that do, all are blocked by Android seccomp. The 6
setsockopt/getsockopt ops require kernel 6.7+, which no Android device ships.

## Feasibility Verdict

### Blocker 1: Android seccomp (Hard Block)

The process is SIGKILL'd on the first io_uring syscall. No workaround exists
without:
- Rooting the device and modifying the seccomp policy, or
- Building a custom Android ROM with io_uring whitelisted

Neither is acceptable for a user-facing app.

### Blocker 2: Desync pipeline incompatibility (Architectural Block)

Even on a hypothetical Android kernel 6.7+ with seccomp lifted:

The desync pipeline in `desync.rs` and `platform/linux.rs` requires
**interleaved setsockopt + I/O** with sequential dependencies. Each
`setsockopt(IP_TTL)` must take effect before the next `splice()` call sends
the segment, because the kernel stamps the current TTL into the outgoing
packet at send time. io_uring's batching model (submit N ops, reap N
completions) provides no advantage here.

Additionally, `vmsplice` (used to gift memory pages into a pipe for zero-copy
send) has no io_uring equivalent and likely never will, since it operates on
virtual memory mappings rather than file descriptors.

### Blocker 3: TCP_INFO polling bottleneck (No io_uring Solution)

`wait_tcp_stage_fd` (`platform/linux.rs:423-447`) is the primary per-packet
latency source in the desync path. It polls `getsockopt(TCP_INFO)` checking
`tcpi_notsent_bytes == 0` in a loop with 1ms sleeps, up to a 5-second
deadline. This pattern exists because Linux provides no event-based
notification for "TCP send queue has drained."

io_uring does not add such a notification. `IORING_OP_POLL` monitors fd
readability/writability, but `EPOLLOUT` fires when the *socket buffer* has
space, not when `tcpi_notsent_bytes` reaches zero. These are different
conditions.

## tokio-uring vs io-uring Crate Comparison

For completeness (future reference if Android seccomp policy changes):

| Aspect | tokio-uring 0.5 | io-uring 0.7 (raw) |
|--------|-----------------|-------------------|
| Async runtime | Requires tokio current-thread only | None |
| Thread model | **Single-threaded** (no `Send` futures) | Any |
| Buffer ownership | Completion-based (buffer moved to kernel) | Same |
| Binary size delta | ~80 KB (tokio already linked) | ~30 KB |
| Android compat | Blocked by seccomp | Blocked by seccomp |
| Desync compat | Cannot interleave setsockopt | Cannot interleave setsockopt |

**Buffer ownership conflict:** tokio-uring uses a completion-based model where
buffers are moved into the kernel on submission and returned on completion.
The current relay loop (`stream_copy.rs:77,146`) reuses a stack-allocated
`[0u8; 16_384]` buffer for every `read` call. Migrating to tokio-uring would
require heap-allocated buffers per I/O operation, adding allocation pressure
on the hot path.

**Thread model conflict:** tokio-uring requires a single-threaded tokio
runtime (`current_thread`). The existing tunnel runtime uses `multi_thread`
with 2 workers (`registry.rs:32-40`). Running both would require maintaining
two separate tokio runtimes in the same process.

## Alternative Optimizations (Recommended)

These address the actual performance characteristics without io_uring:

| Optimization | Impact | Effort | File |
|-------------|--------|--------|------|
| Replace 2-thread relay with `poll(2)` bidirectional copy | 3x thread reduction (768 -> 256 at capacity) | Medium | `relay/stream_copy.rs` |
| Replace `wait_tcp_stage_fd` busy-loop with `epoll(EPOLLOUT)` + `TCP_NOTSENT_LOWAT` | Eliminate 1ms sleep polling on kernel 4.9+ | Low | `platform/linux.rs:423-447` |
| Use `splice(2)` for inbound relay (upstream -> client) | Zero-copy data path, no userspace buffer copy | Low | `relay/stream_copy.rs:71-99` |
| TCP_CORK/TCP_NODELAY toggling around desync writes | Coalesce small segments, reduce packet count | Low | `desync.rs` |

`TCP_NOTSENT_LOWAT` (available since Linux 3.12, all Android versions) provides
an event-based alternative to the TCP_INFO polling loop: set the threshold to
1, then `epoll_wait(EPOLLOUT)` fires when `tcpi_notsent_bytes` drops to zero.
This would eliminate the busy-loop in `wait_tcp_stage_fd` entirely.

## Desktop CLI Exception

`ripdpi-cli` runs on Linux without Android's seccomp restrictions. However:

- The desync setsockopt chain limitation still applies (architectural, not platform)
- Desktop workloads are trivially handled by the current thread model
- Maintaining a separate I/O backend for desktop adds complexity with minimal gain
- The `TCP_NOTSENT_LOWAT` optimization benefits desktop equally without io_uring

## Non-Goals

- Migrating ripdpi-runtime to async/await (would require rewriting the entire
  desync pipeline, session state management, and platform syscall layer)
- Using io_uring for ripdpi-tunnel-core (tokio + epoll is the correct model for
  its `copy_bidirectional` workload; no desync syscalls in the tunnel path)
- Runtime io_uring feature detection (seccomp kills the process; no error to catch)
- Probing io_uring availability via `/proc/sys/kernel` (seccomp is enforced
  regardless of kernel config)

## Revisitation Criteria

This audit should be revisited if any of the following occur:

- Android removes io_uring from its seccomp blocklist (no indication planned;
  Google has publicly committed to blocking it)
- A new Android API provides io_uring access for VPN service apps specifically
- `IORING_OP_SETSOCKOPT` ships on Android kernels (requires 6.7+; earliest
  plausible: Android 17 or 18, circa 2028-2029)
- Linux adds an event-based "send queue drained" notification usable from
  io_uring, making `wait_tcp_stage_fd` obsolete (alternatively, the
  `TCP_NOTSENT_LOWAT` + epoll approach in the alternatives section achieves
  this without io_uring)
