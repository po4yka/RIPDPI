//! Raw-socket injection of the forged decoy ClientHello.
//!
//! ## What is Linux-gated vs portable
//!
//! | Layer | Target gate | Verified on |
//! |---|---|---|
//! | `crate::segment::build_spoof_segment` | **none** (all targets) | macOS unit tests ✓ |
//! | `has_raw_socket_caps` | `cfg(target_os="linux")` | Linux CI |
//! | `send_spoof_segment` | `cfg(target_os="linux")` | Linux CI |
//! | `inject_decoy_segment` (Linux path) | `cfg(target_os="linux")` | Linux CI |
//!
//! The byte-construction and per-method corruption logic ([`crate::segment`])
//! are fully tested on macOS. Only the two syscall sequences — reading the
//! live send-sequence via `TCP_REPAIR`/`TCP_QUEUE_SEQ` and emitting the
//! segment via `SOCK_RAW`/`IPPROTO_RAW` — remain Linux-gated.
//!
//! ## Linux send flow
//!
//! ```text
//! inject_decoy_segment(stream, forged_hello, method)
//!   1. stream.local_addr() / peer_addr()  → SpoofSegmentParams src/dst
//!   2. TCP_INFO snapshot                  → WrongTimestamp tsval + advertised window
//!   3. setsockopt(fd, TCP_REPAIR=19, 1)   → enter repair mode (CAP_NET_ADMIN)
//!   4. setsockopt(fd, TCP_REPAIR_QUEUE=20, 2)  → select SEND queue
//!   5. getsockopt(fd, TCP_QUEUE_SEQ=21)   → read live send seq
//!   6. setsockopt(fd, TCP_REPAIR_QUEUE=20, 1)  → select RECV queue
//!   7. getsockopt(fd, TCP_QUEUE_SEQ=21)   → read live ack
//!   8. build_spoof_segment(params)        → full IPv4+TCP bytes
//!   9. SOCK_RAW / IPPROTO_RAW socket, IP_HDRINCL=1, sendto(dst) → on-wire
//!  10. setsockopt(fd, TCP_REPAIR=19, -1)  → leave repair mode (always runs)
//! ```
//!
//! Steps 8–9 run while `TCP_REPAIR` is still engaged: repair mode quiesces
//! the connection, so the seq/ack snapshot and the raw emit describe one
//! instant of its lifecycle instead of racing concurrent traffic.
//!
//! Step 9 needs `CAP_NET_RAW`; steps 3–7 need `CAP_NET_ADMIN`.
//! [`has_raw_socket_caps`] probes only `CAP_NET_RAW`; the `TCP_REPAIR`
//! `EPERM` from step 3 is surfaced as an `io::Error` and handled gracefully
//! in [`crate::RelaySpoofer::spoof_before_handshake`].
//!
//! ## Exclusivity contract
//!
//! The seq/ack snapshot and the raw emit must describe one instant of the
//! connection's lifecycle. The caller MUST guarantee exclusive access to
//! `stream` for the duration of the call: no concurrent reads or writes on
//! `stream` or any `try_clone()` of it. I/O racing before step 3 or after
//! step 10 would invalidate the captured sequence numbers and land the decoy
//! at a wrong stream offset. Ownership-based enforcement is not possible at
//! the `std` level — `TcpStream` shares one fd across clones — so this
//! contract is documentation, not types.

use std::io;
use std::net::TcpStream;
#[cfg(target_os = "linux")]
use std::os::fd::AsRawFd;

use crate::config::SpoofMethod;
#[cfg(target_os = "linux")]
use crate::segment::{SpoofSegmentParams, build_spoof_segment};

// ── Linux constants ──────────────────────────────────────────────────────────
/// `setsockopt` option level for TCP options.
#[cfg(target_os = "linux")]
const IPPROTO_TCP: libc::c_int = libc::IPPROTO_TCP;
/// `TCP_REPAIR` option number (Linux-specific, not in libc on all versions).
#[cfg(target_os = "linux")]
const TCP_REPAIR: libc::c_int = 19;
/// Enable TCP repair mode.
#[cfg(target_os = "linux")]
const TCP_REPAIR_ON: libc::c_int = 1;
/// Disable TCP repair mode (no window-probe).
#[cfg(target_os = "linux")]
const TCP_REPAIR_OFF_NO_WP: libc::c_int = -1;
/// `TCP_REPAIR_QUEUE` option number.
#[cfg(target_os = "linux")]
const TCP_REPAIR_QUEUE: libc::c_int = 20;
/// `TCP_QUEUE_SEQ` option number.
#[cfg(target_os = "linux")]
const TCP_QUEUE_SEQ: libc::c_int = 21;
/// Select the send queue for `TCP_REPAIR_QUEUE`.
#[cfg(target_os = "linux")]
const TCP_SEND_QUEUE: libc::c_int = 2;
/// Select the receive queue for `TCP_REPAIR_QUEUE`.
#[cfg(target_os = "linux")]
const TCP_RECV_QUEUE: libc::c_int = 1;
/// `TCP_INFO` bit: TCP timestamps negotiated on the connection
/// (linux/tcp.h `TCPI_OPT_TIMESTAMPS`; not in libc's Linux surface, like the
/// other local TCP constants above).
#[cfg(target_os = "linux")]
const TCPI_OPT_TIMESTAMPS: u8 = 0x02;

// ── Capability probe ─────────────────────────────────────────────────────────

/// Probe whether the current process holds the capabilities required for raw
/// TCP segment injection (`CAP_NET_RAW` for `SOCK_RAW`; `CAP_NET_ADMIN` for
/// `TCP_REPAIR`).
///
/// This probe checks only `CAP_NET_RAW` (socket creation). `TCP_REPAIR`
/// failure is surfaced as an `io::Error` from `send_spoof_segment` and is
/// handled gracefully by the caller.
///
/// The probe uses `SOCK_RAW / IPPROTO_TCP` while injection sends via
/// `SOCK_RAW / IPPROTO_RAW`; both protocols gate raw socket creation on the
/// same `CAP_NET_RAW` capability, so the probe result transfers.
///
/// Always returns `false` on non-Linux.
#[cfg(target_os = "linux")]
#[must_use]
pub fn has_raw_socket_caps() -> bool {
    // SAFETY: socket(2) with constant arguments has no pointer operands and no
    // memory-safety preconditions. It returns a new fd >= 0 on success or -1
    // on failure (EPERM/EACCES = no CAP_NET_RAW). The returned fd is closed
    // immediately below; we never dereference it.
    let fd = unsafe { libc::socket(libc::AF_INET, libc::SOCK_RAW, libc::IPPROTO_TCP) };
    if fd < 0 {
        return false;
    }
    // SAFETY: `fd` is the file descriptor from the successful socket(2) call
    // above. It is valid and open. We close it exactly once here and do not
    // use `fd` after this point. Ignoring the return value of close(2) is
    // sound for a probe fd with no buffered data.
    unsafe {
        libc::close(fd);
    }
    true
}

/// Non-Linux stub: capabilities are never available off Linux.
#[cfg(not(target_os = "linux"))]
#[must_use]
pub fn has_raw_socket_caps() -> bool {
    false
}

// ── Linux send path ──────────────────────────────────────────────────────────

/// Read the live TCP send-sequence and ACK numbers from `stream` using
/// `TCP_REPAIR` / `TCP_QUEUE_SEQ`, build the corrupted segment via
/// [`build_spoof_segment`], and emit it via a `SOCK_RAW / IPPROTO_RAW`
/// socket with `IP_HDRINCL`.
///
/// `TCP_REPAIR` is left disabled (mode `−1`) after this call regardless of
/// whether the segment send succeeds, so the real connection is not affected.
///
/// Requires `CAP_NET_ADMIN` (for `TCP_REPAIR`) and `CAP_NET_RAW` (for
/// `SOCK_RAW`). Returns `Err(EPERM)` if either capability is absent.
///
/// Only IPv4 connections are supported; IPv6 returns `Unsupported`.
///
/// # Exclusivity contract
///
/// The caller MUST guarantee exclusive access to `stream` for the duration
/// of the call — no concurrent reads or writes on `stream` or any
/// `try_clone()` of it. The seq/ack snapshot and the raw emit must describe
/// one instant of the connection's lifecycle; racing I/O invalidates the
/// snapshot and lands the decoy at a wrong stream offset. See the module
/// docs for why this cannot be enforced by types.
#[cfg(target_os = "linux")]
pub fn send_spoof_segment(stream: &TcpStream, forged_hello: &[u8], method: SpoofMethod) -> io::Result<()> {
    use std::net::SocketAddr;

    use socket2::{Domain, Protocol, SockAddr, Socket, Type};

    let local = stream.local_addr()?;
    let peer = stream.peer_addr()?;

    let (src_ip, src_port, dst_ip, dst_port) = match (local, peer) {
        (SocketAddr::V4(l), SocketAddr::V4(p)) => (*l.ip(), l.port(), *p.ip(), p.port()),
        _ => {
            return Err(io::Error::new(
                io::ErrorKind::Unsupported,
                "tls-spoof raw injection only supports IPv4 connections",
            ));
        }
    };

    let fd = stream.as_raw_fd();

    // Snapshot fingerprint inputs before entering repair mode. One TCP_INFO
    // read feeds both the WrongTimestamp tsval and the advertised window.
    let info = get_tcp_info(fd)?;
    let tsval = stale_timestamp_value(&info, fd)?;
    let window = advertised_window_field(&info);

    // Enter TCP_REPAIR mode so we can read the live sequence numbers without
    // disrupting the connection's state machine.
    set_tcp_int_opt(fd, TCP_REPAIR, TCP_REPAIR_ON)?;

    // Always disable TCP_REPAIR before returning, even on error.  We use a
    // closure so the `?` operator works naturally inside while the cleanup
    // runs outside.
    let result = (|| -> io::Result<()> {
        // Read send-sequence: select send queue, then getsockopt(TCP_QUEUE_SEQ).
        set_tcp_int_opt(fd, TCP_REPAIR_QUEUE, TCP_SEND_QUEUE)?;
        let seq = get_tcp_u32_opt(fd, TCP_QUEUE_SEQ)?;

        // Read ACK (peer's send-seq = our recv-seq): select recv queue.
        set_tcp_int_opt(fd, TCP_REPAIR_QUEUE, TCP_RECV_QUEUE)?;
        let ack = get_tcp_u32_opt(fd, TCP_QUEUE_SEQ)?;

        let ttl = get_stream_ttl(stream).unwrap_or(64);

        let params = SpoofSegmentParams {
            src_ip,
            src_port,
            dst_ip,
            dst_port,
            seq,
            ack,
            window,
            ttl,
            ip_id: (seq ^ (process_entropy() as u32)) as u16,
            payload: forged_hello.to_vec(),
            method,
            tsval,
            ack_delta: process_ack_delta(),
        };

        let packet = build_spoof_segment(&params)?;

        // Open SOCK_RAW / IPPROTO_RAW with IP_HDRINCL so we supply the full
        // IPv4 header.
        let raw = Socket::new(Domain::IPV4, Type::from(libc::SOCK_RAW), Some(Protocol::from(libc::IPPROTO_RAW)))?;
        // SAFETY: `raw.as_raw_fd()` is a valid open socket fd. `IP_HDRINCL`
        // expects a `c_int` value (1 = enabled); the pointer operand is a
        // valid reference to a stack-allocated `i32`. The fd is not closed or
        // moved while this reference is live.
        unsafe {
            let enable: libc::c_int = 1;
            let rc = libc::setsockopt(
                raw.as_raw_fd(),
                libc::IPPROTO_IP,
                libc::IP_HDRINCL,
                (&enable as *const libc::c_int).cast(),
                std::mem::size_of::<libc::c_int>() as libc::socklen_t,
            );
            if rc != 0 {
                return Err(io::Error::last_os_error());
            }
        }

        let dst_sock = SockAddr::from(std::net::SocketAddrV4::new(dst_ip, dst_port));
        raw.send_to(&packet, &dst_sock)?;
        Ok(())
    })();

    // Disable TCP_REPAIR unconditionally; ignore errors (best-effort cleanup).
    let _ = set_tcp_int_opt(fd, TCP_REPAIR, TCP_REPAIR_OFF_NO_WP);
    result
}

/// Read the live socket TTL via `getsockopt(IP_TTL)`.
#[cfg(target_os = "linux")]
fn get_stream_ttl(stream: &TcpStream) -> io::Result<u8> {
    let fd = stream.as_raw_fd();
    let mut val: libc::c_int = 0;
    let mut len = std::mem::size_of::<libc::c_int>() as libc::socklen_t;
    // SAFETY: `fd` is a valid TCP socket; `val` is a stack-allocated `c_int`
    // whose address is valid for the duration of the syscall; `len` is the
    // exact size of `c_int`. `IP_TTL` getsockopt writes a `c_int` ≤ 255.
    let rc = unsafe {
        libc::getsockopt(fd, libc::IPPROTO_IP, libc::IP_TTL, (&mut val as *mut libc::c_int).cast(), &mut len)
    };
    if rc != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(val.clamp(1, 255) as u8)
}

/// `setsockopt` a `c_int` value for an `IPPROTO_TCP` option.
#[cfg(target_os = "linux")]
fn set_tcp_int_opt(fd: libc::c_int, opt: libc::c_int, val: libc::c_int) -> io::Result<()> {
    // SAFETY: `fd` is a valid TCP socket at all call sites (from
    // `stream.as_raw_fd()`). `val` is a stack-allocated `c_int`; its address
    // is valid for the duration of the syscall. `IPPROTO_TCP` + `opt` are
    // integer constants with known semantics (TCP_REPAIR, TCP_REPAIR_QUEUE,
    // TCP_QUEUE_SEQ). No aliasing hazard — the kernel copies the value.
    let rc = unsafe {
        libc::setsockopt(
            fd,
            IPPROTO_TCP,
            opt,
            (&val as *const libc::c_int).cast(),
            std::mem::size_of::<libc::c_int>() as libc::socklen_t,
        )
    };
    if rc != 0 { Err(io::Error::last_os_error()) } else { Ok(()) }
}

/// `getsockopt` a `u32` value for an `IPPROTO_TCP` option.
#[cfg(target_os = "linux")]
fn get_tcp_u32_opt(fd: libc::c_int, opt: libc::c_int) -> io::Result<u32> {
    let mut val: u32 = 0;
    let mut len = std::mem::size_of::<u32>() as libc::socklen_t;
    // SAFETY: `fd` is a valid TCP socket in repair mode at all call sites.
    // `val` is a stack-allocated `u32`; its address is valid for the duration
    // of the syscall. `TCP_QUEUE_SEQ` getsockopt writes exactly 4 bytes into
    // `*val`. `len` is initialised to `sizeof(u32)` and may be reduced by the
    // kernel (we do not read past the written portion because we only return
    // the whole `u32`).
    let rc = unsafe { libc::getsockopt(fd, IPPROTO_TCP, opt, (&mut val as *mut u32).cast(), &mut len) };
    if rc != 0 { Err(io::Error::last_os_error()) } else { Ok(val) }
}

/// Read the live `TCP_INFO` snapshot for a TCP socket fd.
#[cfg(target_os = "linux")]
fn get_tcp_info(fd: libc::c_int) -> io::Result<libc::tcp_info> {
    let mut info: libc::tcp_info = unsafe { std::mem::zeroed() };
    let mut len = std::mem::size_of::<libc::tcp_info>() as libc::socklen_t;
    // SAFETY: `fd` is a valid TCP socket; `info` is a stack-allocated all-zero
    // `tcp_info` (plain-old-data, valid as an output buffer); `len` is its
    // exact size. The kernel writes at most `*len` bytes into it.
    let rc = unsafe {
        libc::getsockopt(fd, IPPROTO_TCP, libc::TCP_INFO, (&mut info as *mut libc::tcp_info).cast(), &mut len)
    };
    if rc != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(info)
}

/// Return the stale TCP timestamp value for the `WrongTimestamp` decoy.
///
/// Returns `None` when the upstream connection did not negotiate TCP
/// timestamps (`TCP_INFO.tcpi_options` lacks `TCPI_OPT_TIMESTAMPS`): without
/// negotiation the server ignores the TS option entirely and would ACCEPT the
/// decoy segment, corrupting the stream — so the caller must fail closed
/// instead of emitting. Otherwise returns the live `TCP_TIMESTAMP` value minus
/// [`STALE_TS_DELTA`]: older than every TSval the server has seen, which is
/// what PAWS (RFC 7323 §5.3) actually rejects.
#[cfg(target_os = "linux")]
fn stale_timestamp_value(info: &libc::tcp_info, fd: libc::c_int) -> io::Result<Option<u32>> {
    if info.tcpi_options & TCPI_OPT_TIMESTAMPS == 0 {
        return Ok(None);
    }

    let mut ts: libc::c_uint = 0;
    let mut ts_len = std::mem::size_of::<libc::c_uint>() as libc::socklen_t;
    // SAFETY: `fd` is a valid TCP socket; `ts` is a stack-allocated `c_uint`
    // valid for the duration of the syscall; the kernel writes exactly
    // `sizeof(c_uint)` bytes for TCP_TIMESTAMP.
    let rc = unsafe {
        libc::getsockopt(fd, IPPROTO_TCP, libc::TCP_TIMESTAMP, (&mut ts as *mut libc::c_uint).cast(), &mut ts_len)
    };
    if rc != 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(Some(ts.saturating_sub(crate::segment::STALE_TS_DELTA)))
}

/// Process-random 64-bit seed, computed once and stable for the process
/// lifetime. Entropy comes from `RandomState`'s OS-seeded hash keys mixed
/// with the pid.
#[cfg(target_os = "linux")]
fn process_entropy() -> u64 {
    // `build_hasher` and `finish` resolve through the `BuildHasher`/`Hasher`
    // traits, which are not in the prelude; without these imports the
    // Linux-only body fails E0599.
    use std::hash::{BuildHasher as _, Hasher as _};
    static SEED: std::sync::OnceLock<u64> = std::sync::OnceLock::new();
    *SEED.get_or_init(|| {
        std::collections::hash_map::RandomState::new().build_hasher().finish() ^ (u64::from(std::process::id()) << 32)
    })
}

/// Process-random nonzero ACK offset for `WrongAck` decoys.
///
/// A fixed well-known offset would be a static signature DPI vendors can scan
/// for; deriving one value per process keeps every deployment (and every relay
/// restart) different while staying stable for the process lifetime.
#[cfg(target_os = "linux")]
fn process_ack_delta() -> u32 {
    (process_entropy() as u32) | 1
}

/// Derive the TCP window-field value the kernel would currently advertise on/// this connection, so the decoy segment's window matches the rest of the
/// flow instead of a fixed fingerprint-breaking constant.
///
/// The outgoing window field carries our receive window in unscaled units —
/// the peer left-shifts it by the wscale we announced in the SYN — so the
/// value is `tcpi_rcv_wnd >> tcpi_rcv_wscale`. libc packs both 4-bit scales
/// into one byte (`tcpi_snd_rcv_wscale`): low nibble snd, high nibble rcv.
///
/// A zero or overflowing result means either a genuinely collapsed
/// zero-window or an old kernel that does not fill `tcpi_rcv_wnd` (< 5.4);
/// both fall back to the previous fixed 65535 rather than emit a degenerate
/// window field.
#[cfg(target_os = "linux")]
fn advertised_window_field(info: &libc::tcp_info) -> u16 {
    let rcv_wscale = info.tcpi_snd_rcv_wscale >> 4;
    match u16::try_from(info.tcpi_rcv_wnd >> rcv_wscale) {
        Ok(field) if field > 0 => field,
        _ => u16::MAX,
    }
}

// ── Public inject entry point ────────────────────────────────────────────────

/// Inject `forged_hello` as a single corrupted TCP segment on the connection
/// underlying `stream`, using `method` to choose the corruption.
///
/// ## Linux
///
/// Calls [`send_spoof_segment`]: reads the live seq/ack via `TCP_REPAIR`,
/// builds the segment bytes via [`build_spoof_segment`], and emits them via
/// `SOCK_RAW / IPPROTO_RAW`. The send path is **Linux-CI verified**.
///
/// # Exclusivity contract
///
/// The caller MUST guarantee exclusive access to `stream` for the duration
/// of the call — no concurrent I/O on `stream` or any `try_clone()` of it;
/// see [`send_spoof_segment`] and the module docs.
///
/// ## Non-Linux
///
/// Returns `Err(Unsupported)`. The segment builder ([`build_spoof_segment`])
/// is still exercised and all its unit tests run on macOS; only the syscall
/// send path is gated.
#[cfg(target_os = "linux")]
pub fn inject_decoy_segment(stream: &TcpStream, forged_hello: &[u8], method: SpoofMethod) -> io::Result<()> {
    send_spoof_segment(stream, forged_hello, method)
}

/// Non-Linux stub.
#[cfg(not(target_os = "linux"))]
pub fn inject_decoy_segment(stream: &TcpStream, _forged_hello: &[u8], _method: SpoofMethod) -> io::Result<()> {
    // Silence the unused-variable warning for `stream` while keeping the
    // function signature identical to the Linux version.
    let _ = stream;
    Err(io::Error::new(io::ErrorKind::Unsupported, "raw-socket injection is Linux-only"))
}

// ── Tests ────────────────────────────────────────────────────────────────────

#[cfg(test)]
mod tests {
    use super::*;

    #[cfg(not(target_os = "linux"))]
    #[test]
    fn caps_false_off_linux() {
        assert!(!has_raw_socket_caps());
    }

    #[test]
    fn inject_is_unsupported_on_non_linux_or_unprivileged() {
        // Loopback only — exempt from the VpnService.protect invariant.
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let stream = std::net::TcpStream::connect(addr).unwrap();
        let err = inject_decoy_segment(&stream, &[0u8; 16], SpoofMethod::WrongAck).unwrap_err();
        // On macOS: Unsupported (non-Linux stub).
        // On Linux without CAP_NET_ADMIN: EPERM from TCP_REPAIR setsockopt.
        // On Linux with caps: may succeed (CI).
        #[cfg(not(target_os = "linux"))]
        assert_eq!(err.kind(), io::ErrorKind::Unsupported);
        #[cfg(target_os = "linux")]
        assert!(
            err.kind() == io::ErrorKind::Unsupported
                || err.kind() == io::ErrorKind::PermissionDenied
                || err.kind() == io::ErrorKind::Other,
            "Linux without caps should get PermissionDenied or Unsupported, got {err}"
        );
    }

    /// On Linux *with* `CAP_NET_ADMIN` + `CAP_NET_RAW` the send succeeds.
    /// This test is skipped on macOS (non-Linux compile) and skipped on
    /// unprivileged Linux (TCP_REPAIR probe returns Err).
    ///
    /// Linux CI verification path: run as root or with the capabilities granted.
    #[cfg(target_os = "linux")]
    #[test]
    fn linux_inject_succeeds_when_privileged() {
        if !has_raw_socket_caps() {
            // No CAP_NET_RAW — expected on unprivileged CI hosts. Skip.
            return;
        }
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        let stream = std::net::TcpStream::connect(addr).unwrap();
        // We accept either Ok (privileged) or EPERM (CAP_NET_RAW present but
        // CAP_NET_ADMIN absent — unusual split, but valid to handle).
        let result = inject_decoy_segment(&stream, b"fake hello", SpoofMethod::WrongAck);
        match result {
            Ok(()) => {}
            Err(e) if e.kind() == io::ErrorKind::PermissionDenied => {}
            Err(e) => panic!("unexpected error on privileged Linux: {e}"),
        }
    }
}
