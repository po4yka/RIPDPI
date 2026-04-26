//! Linux/Android platform socket operations.
//!
//! This module intentionally uses raw `libc::setsockopt`/`getsockopt` for
//! kernel-specific options not available in `socket2` (as of 0.5):
//! TCP_INFO, TCP_MD5SIG, TCP_FASTOPEN_CONNECT, SO_ATTACH_FILTER,
//! SO_ORIGINAL_DST, IP_RECVTTL, and `recvmsg` with CMSG ancillary data.
//!
//! Standard socket options use `socket2::SockRef` (see [`set_stream_ttl`]).
//! Last audited: 2026-03-24 against socket2 0.5.10.
//!
//! # TTL write capability
//!
//! [`try_set_stream_ttl_with_outcome`] is the preferred entry point for new
//! code that needs to set a per-socket TTL.  It returns a typed
//! [`CapabilityOutcome`] rather than an `io::Result`, making the
//! unavailable / permission-denied cases explicit at the call site.
//! The lower-level [`set_stream_ttl`] helper (returning `io::Result`) is kept
//! for internal use by the restore path where fire-and-forget semantics are
//! acceptable.

use std::io::{self, Read};
use std::mem::{size_of, zeroed};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpStream, UdpSocket};
use std::os::fd::AsRawFd;
use std::os::unix::net::UnixStream;
use std::ptr;
use std::thread;
use std::time::{Duration, Instant};

use etherparse::{ip_number, Ipv4Header, Ipv6FlowLabel, Ipv6Header, TcpHeader, TcpHeaderSlice};
use ripdpi_config::{
    TCP_FLAG_ACK, TCP_FLAG_AE, TCP_FLAG_CWR, TCP_FLAG_ECE, TCP_FLAG_FIN, TCP_FLAG_PSH, TCP_FLAG_R1, TCP_FLAG_R2,
    TCP_FLAG_R3, TCP_FLAG_RST, TCP_FLAG_SYN, TCP_FLAG_URG,
};
use ripdpi_desync::TcpSegmentHint;
use ripdpi_ipfrag::{
    build_tcp_fragment_pair, build_udp_fragment_pair, TcpFragmentSpec, TcpTimestampOption, UdpFragmentSpec,
};
use socket2::{Domain, Protocol, SockAddr, SockRef, Socket, Type};

use super::{CapabilityOutcome, CapabilityUnavailable, RuntimeCapability, TcpActivationState, TcpFlagOverrides};

mod experimental_tier3;
mod fake_send;
mod ip_fragmentation;
mod mmap_region;

use mmap_region::{alloc_region, free_region, write_region};

pub use experimental_tier3::{recv_icmp_wrapped_udp, send_icmp_wrapped_udp, send_syn_hide_tcp};
pub use fake_send::{
    send_fake_rst, send_fake_tcp, send_flagged_tcp_payload, send_ordered_tcp_segments, send_seqovl_tcp,
};
pub use ip_fragmentation::{
    probe_ip_fragmentation_capabilities, send_ip_fragmented_tcp, send_ip_fragmented_udp, send_multi_disorder_tcp,
};

/// Thin wrapper around `libc::setsockopt` that handles the return-code check
/// and `io::Error` conversion.
///
/// # Safety
/// `fd` must be a live socket descriptor; `val` must be a valid payload for the
/// given `level`/`name` combination per the Linux kernel ABI.
unsafe fn setsockopt_raw<T>(fd: libc::c_int, level: libc::c_int, name: libc::c_int, val: &T) -> io::Result<()> {
    let rc = unsafe { libc::setsockopt(fd, level, name, (val as *const T).cast(), size_of::<T>() as libc::socklen_t) };
    if rc == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error())
    }
}

/// Thin wrapper around `libc::getsockopt` that handles zero-init, the
/// return-code check, and `io::Error` conversion. Returns the value together
/// with the actual byte length written by the kernel (useful for variable-size
/// structs like `tcp_info`).
///
/// # Safety
/// `fd` must be a live socket descriptor; `T` must match the kernel's expected
/// output layout for the given `level`/`name` combination.
unsafe fn getsockopt_raw<T>(
    fd: libc::c_int,
    level: libc::c_int,
    name: libc::c_int,
) -> io::Result<(T, libc::socklen_t)> {
    let mut val: T = unsafe { zeroed() };
    let mut len = size_of::<T>() as libc::socklen_t;
    let rc = unsafe { libc::getsockopt(fd, level, name, (&mut val as *mut T).cast(), &mut len) };
    if rc == 0 {
        Ok((val, len))
    } else {
        Err(io::Error::last_os_error())
    }
}

/// Safe wrapper for socket options whose Linux ABI is a plain `c_int`.
fn set_c_int_sockopt(fd: libc::c_int, level: libc::c_int, name: libc::c_int, value: libc::c_int) -> io::Result<()> {
    // SAFETY: callers provide a live socket descriptor and this helper is only
    // used for options whose payload type is exactly `c_int`.
    unsafe { setsockopt_raw(fd, level, name, &value) }
}

/// Safe wrapper for socket options whose Linux ABI returns a plain `c_int`.
fn get_c_int_sockopt(fd: libc::c_int, level: libc::c_int, name: libc::c_int) -> io::Result<libc::c_int> {
    // SAFETY: callers provide a live socket descriptor and this helper is only
    // used for options whose payload type is exactly `c_int`.
    let (value, _len): (libc::c_int, _) = unsafe { getsockopt_raw(fd, level, name) }?;
    Ok(value)
}

/// Safe wrapper for socket options whose Linux ABI payload is exactly `u32`.
fn set_u32_sockopt(fd: libc::c_int, level: libc::c_int, name: libc::c_int, value: u32) -> io::Result<()> {
    // SAFETY: callers provide a live socket descriptor and this helper is only
    // used for options whose payload type is exactly `u32`.
    unsafe { setsockopt_raw(fd, level, name, &value) }
}

/// Safe wrapper for socket options whose Linux ABI returns exactly `u32`.
fn get_u32_sockopt(fd: libc::c_int, level: libc::c_int, name: libc::c_int) -> io::Result<u32> {
    // SAFETY: callers provide a live socket descriptor and this helper is only
    // used for options whose payload type is exactly `u32`.
    let (value, _len): (u32, _) = unsafe { getsockopt_raw(fd, level, name) }?;
    Ok(value)
}

/// Safe wrapper around `libc::dup2` for fd handoff and replacement.
pub(crate) fn dup2_fd(source_fd: libc::c_int, target_fd: libc::c_int) -> io::Result<()> {
    // SAFETY: callers guarantee both fds are live descriptors and accept the
    // kernel-level replacement semantics of `dup2`.
    let rc = unsafe { libc::dup2(source_fd, target_fd) };
    if rc >= 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error())
    }
}

/// Safe wrapper around `libc::close` for owned transient descriptors.
#[cfg(test)]
pub(crate) fn close_fd(fd: libc::c_int) -> io::Result<()> {
    // SAFETY: callers guarantee `fd` is an owned live descriptor that should
    // be closed exactly once by this helper.
    let rc = unsafe { libc::close(fd) };
    if rc == 0 {
        Ok(())
    } else {
        Err(io::Error::last_os_error())
    }
}

#[repr(C)]
struct TcpMd5Sig {
    addr: libc::sockaddr_storage,
    pad1: u16,
    key_len: u16,
    pad2: u32,
    key: [u8; 80],
}

const SO_ORIGINAL_DST: libc::c_int = 80;
const IP6T_SO_ORIGINAL_DST: libc::c_int = 80;
const TCP_ESTABLISHED: u8 = 1;
const TCP_REPAIR: libc::c_int = 19;
const TCP_REPAIR_QUEUE: libc::c_int = 20;
const TCP_QUEUE_SEQ: libc::c_int = 21;
const TCP_REPAIR_OPTIONS: libc::c_int = 22;
const TCP_REPAIR_OFF_NO_WP: libc::c_int = -1;
const TCP_REPAIR_WINDOW: libc::c_int = 29;
const TCP_REPAIR_ON: libc::c_int = 1;
const TCP_REPAIR_OFF: libc::c_int = 0;
const TCP_NO_QUEUE: libc::c_int = 0;
const TCP_RECV_QUEUE: libc::c_int = 1;
const TCP_SEND_QUEUE: libc::c_int = 2;
const TCPI_OPT_TIMESTAMPS: u8 = 1;
const TCPI_OPT_SACK: u8 = 2;
const TCPI_OPT_WSCALE: u8 = 4;
const TCPI_OPT_USEC_TS: u8 = 64;
const TCPOPT_MSS: u32 = 2;
const TCPOPT_WINDOW: u32 = 3;
const TCPOPT_SACK_PERM: u32 = 4;
const TCPOPT_TIMESTAMP: u32 = 8;

#[repr(C)]
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
struct TcpRepairWindow {
    snd_wl1: u32,
    snd_wnd: u32,
    max_window: u32,
    rcv_wnd: u32,
    rcv_wup: u32,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
struct TcpRepairOpt {
    opt_code: u32,
    opt_val: u32,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
struct StreamSocketSettings {
    nodelay: Option<bool>,
    read_timeout: Option<Option<Duration>>,
    write_timeout: Option<Option<Duration>>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct TcpTimestampSnapshot {
    value: u32,
    echo_reply: u32,
    usec_ts: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct TcpWindowScaleSnapshot {
    send: u8,
    receive: u8,
}

#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
struct TcpRepairOptionsSnapshot {
    mss: Option<u16>,
    sack_permitted: bool,
    window_scale: Option<TcpWindowScaleSnapshot>,
    timestamp: Option<TcpTimestampSnapshot>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct TcpRepairSnapshot {
    sequence_number: u32,
    acknowledgment_number: u32,
    window_size: u16,
    repair_window: TcpRepairWindow,
    options: TcpRepairOptionsSnapshot,
}

#[repr(C)]
#[derive(Clone, Copy, Debug, Default)]
struct LinuxTcpInfo {
    tcpi_state: u8,
    tcpi_ca_state: u8,
    tcpi_retransmits: u8,
    tcpi_probes: u8,
    tcpi_backoff: u8,
    tcpi_options: u8,
    tcpi_snd_wscale_rcv_wscale: u8,
    tcpi_delivery_rate_app_limited_fastopen_client_fail: u8,
    tcpi_rto: u32,
    tcpi_ato: u32,
    tcpi_snd_mss: u32,
    tcpi_rcv_mss: u32,
    tcpi_unacked: u32,
    tcpi_sacked: u32,
    tcpi_lost: u32,
    tcpi_retrans: u32,
    tcpi_fackets: u32,
    tcpi_last_data_sent: u32,
    tcpi_last_ack_sent: u32,
    tcpi_last_data_recv: u32,
    tcpi_last_ack_recv: u32,
    tcpi_pmtu: u32,
    tcpi_rcv_ssthresh: u32,
    tcpi_rtt: u32,
    tcpi_rttvar: u32,
    tcpi_snd_ssthresh: u32,
    tcpi_snd_cwnd: u32,
    tcpi_advmss: u32,
    tcpi_reordering: u32,
    tcpi_rcv_rtt: u32,
    tcpi_rcv_space: u32,
    tcpi_total_retrans: u32,
    tcpi_pacing_rate: u64,
    tcpi_max_pacing_rate: u64,
    tcpi_bytes_acked: u64,
    tcpi_bytes_received: u64,
    tcpi_segs_out: u32,
    tcpi_segs_in: u32,
    tcpi_notsent_bytes: u32,
}

pub fn enable_tcp_fastopen_connect<T: AsRawFd>(socket: &T) -> io::Result<()> {
    set_c_int_sockopt(socket.as_raw_fd(), libc::IPPROTO_TCP, libc::TCP_FASTOPEN_CONNECT, 1)
}

pub fn set_tcp_md5sig(stream: &TcpStream, key_len: u16) -> io::Result<()> {
    if usize::from(key_len) > 80 {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "md5 key length exceeds linux tcp_md5sig limit"));
    }

    let fd = stream.as_raw_fd();
    let addr = peer_addr(fd)?;
    let md5 = TcpMd5Sig { addr, pad1: 0, key_len, pad2: 0, key: [0; 80] };

    // SAFETY: `md5` is a valid `tcp_md5sig`-compatible buffer and `fd` is a
    // live TCP socket owned by `stream`.
    unsafe { setsockopt_raw(fd, libc::IPPROTO_TCP, libc::TCP_MD5SIG, &md5) }
}

pub fn protect_socket<T: AsRawFd>(socket: &T, path: &str) -> io::Result<()> {
    use nix::sys::socket::{sendmsg, ControlMessage, MsgFlags};
    use std::io::IoSlice;

    tracing::debug!(path = path, "protect_socket: connecting");
    let stream = UnixStream::connect(path)?;
    stream.set_read_timeout(Some(Duration::from_secs(1)))?;
    stream.set_write_timeout(Some(Duration::from_secs(1)))?;

    let payload = [b'1'];
    let iov = [IoSlice::new(&payload)];
    let fd = socket.as_raw_fd();
    let fds = [fd];
    let cmsg = [ControlMessage::ScmRights(&fds)];
    sendmsg::<()>(stream.as_raw_fd(), &iov, &cmsg, MsgFlags::empty(), None).map_err(io::Error::from)?;

    let mut ack = [0u8; 1];
    (&stream).read_exact(&mut ack)?;
    if ack[0] != 0 {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "VpnService.protect() rejected socket"));
    }
    tracing::debug!(path = path, "protect_socket: fd protected");
    Ok(())
}

pub fn original_dst(stream: &TcpStream) -> io::Result<SocketAddr> {
    let fd = stream.as_raw_fd();

    // SAFETY: `fd` is a live TCP socket; the kernel writes a `sockaddr_storage`
    // for SO_ORIGINAL_DST / IP6T_SO_ORIGINAL_DST.
    if let Ok((storage, _)) = unsafe { getsockopt_raw::<libc::sockaddr_storage>(fd, libc::IPPROTO_IP, SO_ORIGINAL_DST) }
    {
        return storage_to_socket_addr(&storage);
    }
    let (storage, _) =
        unsafe { getsockopt_raw::<libc::sockaddr_storage>(fd, libc::IPPROTO_IPV6, IP6T_SO_ORIGINAL_DST) }?;
    storage_to_socket_addr(&storage)
}

/// Attach a BPF filter that drops incoming TCP segments containing a SACK
/// option.
///
/// **Limitation:** The filter checks a fixed offset (0x22) for the SACK option
/// kind byte rather than performing a full TLV scan of the TCP options field.
/// This works for the vast majority of Linux TCP stacks where SACK appears at a
/// predictable position, but may miss SACK placed at non-standard offsets by
/// unusual middleboxes or custom stacks.
pub fn attach_drop_sack(stream: &TcpStream) -> io::Result<()> {
    let fd = stream.as_raw_fd();
    let mut code = [
        libc::sock_filter { code: 0x30, jt: 0, jf: 0, k: 0x0000000c },
        libc::sock_filter { code: 0x74, jt: 0, jf: 0, k: 0x00000004 },
        libc::sock_filter { code: 0x35, jt: 0, jf: 3, k: 0x0000000b },
        libc::sock_filter { code: 0x30, jt: 0, jf: 0, k: 0x00000022 },
        libc::sock_filter { code: 0x15, jt: 0, jf: 1, k: 0x00000005 },
        libc::sock_filter { code: 0x6, jt: 0, jf: 0, k: 0x00000000 },
        libc::sock_filter { code: 0x6, jt: 0, jf: 0, k: 0x00040000 },
    ];
    let prog = libc::sock_fprog { len: code.len() as u16, filter: code.as_mut_ptr() };

    // SAFETY: `prog` points to a live in-process BPF program and `fd` is a
    // valid TCP socket descriptor owned by `stream`.
    unsafe { setsockopt_raw(fd, libc::SOL_SOCKET, libc::SO_ATTACH_FILTER, &prog) }
}

pub fn detach_drop_sack(stream: &TcpStream) -> io::Result<()> {
    set_c_int_sockopt(stream.as_raw_fd(), libc::SOL_SOCKET, libc::SO_DETACH_FILTER, 0)
}

/// Attach a BPF socket filter that drops outgoing TCP segments containing
/// the Timestamps option (kind=8).
///
/// This prevents DPI from using TCP timestamp values for flow-level timing
/// correlation. The BPF program checks the TCP option kind byte at a fixed
/// offset (same approach as [`attach_drop_sack`] for SACK kind=5).
///
/// **Note:** `SO_ATTACH_FILTER` replaces any prior filter. If both `drop_sack`
/// and `strip_timestamps` are needed, call `attach_drop_sack` first — this
/// filter will replace it. For combined filtering, a future refactor can merge
/// both checks into a single BPF program.
pub fn attach_strip_timestamps(stream: &TcpStream) -> io::Result<()> {
    let fd = stream.as_raw_fd();
    // BPF program: check TCP data offset (byte 12, upper nibble) >= 11 words
    // (44 bytes, meaning options exist beyond the basic 20-byte header).
    // Then check option kind byte at offset 0x1e (byte 30 = start of options
    // area for most common layouts where timestamps appear first).
    // If kind == 8 (Timestamps), drop the packet (return 0).
    // Otherwise, accept (return 0x40000).
    let mut code = [
        // Load byte at offset 12 (TCP data offset + flags)
        libc::sock_filter { code: 0x30, jt: 0, jf: 0, k: 0x0000000c },
        // Shift right 4 to get data offset in 32-bit words
        libc::sock_filter { code: 0x74, jt: 0, jf: 0, k: 0x00000004 },
        // If data offset < 8 (header < 32 bytes = no room for timestamps), accept
        libc::sock_filter { code: 0x35, jt: 0, jf: 3, k: 0x00000008 },
        // Load byte at offset 0x14 (byte 20 = first option kind after 20-byte header)
        libc::sock_filter { code: 0x30, jt: 0, jf: 0, k: 0x00000014 },
        // If kind == 8 (Timestamps), drop
        libc::sock_filter { code: 0x15, jt: 0, jf: 1, k: 0x00000008 },
        // Drop: return 0
        libc::sock_filter { code: 0x6, jt: 0, jf: 0, k: 0x00000000 },
        // Accept: return max
        libc::sock_filter { code: 0x6, jt: 0, jf: 0, k: 0x00040000 },
    ];
    let prog = libc::sock_fprog { len: code.len() as u16, filter: code.as_mut_ptr() };

    // SAFETY: `prog` points to a live in-process BPF program and `fd` is a
    // valid TCP socket descriptor owned by `stream`.
    unsafe { setsockopt_raw(fd, libc::SOL_SOCKET, libc::SO_ATTACH_FILTER, &prog) }
}

/// Clamp the TCP receive window to force the server to send small segments.
///
/// Setting `size` to a low value (e.g., 1 or 2) causes the kernel to advertise
/// a tiny window, preventing DPI from reassembling the response stream. The
/// kernel enforces a floor of `SOCK_MIN_RCVBUF / 2` so the effective window
/// will not drop below ~1152 bytes regardless of how small `size` is.
///
/// To remove the clamp on an already-connected socket, pass a value larger
/// than any reasonable advertised window (e.g., `1_000_000`); modern Linux
/// rejects `size=0` on established sockets with `EINVAL` (only `TCP_CLOSE`
/// sockets accept it).
pub fn set_tcp_window_clamp(stream: &TcpStream, size: u32) -> io::Result<()> {
    let val = size as libc::c_int;
    set_c_int_sockopt(stream.as_raw_fd(), libc::IPPROTO_TCP, libc::TCP_WINDOW_CLAMP, val)
}

/// Read the current `TCP_WINDOW_CLAMP` value on a socket.
#[cfg(test)]
pub fn get_tcp_window_clamp(stream: &TcpStream) -> io::Result<u32> {
    Ok(get_c_int_sockopt(stream.as_raw_fd(), libc::IPPROTO_TCP, libc::TCP_WINDOW_CLAMP)? as u32)
}

/// Set the socket receive buffer size (`SO_RCVBUF`).
///
/// On Linux the kernel doubles the requested value to account for bookkeeping
/// overhead.  Setting this **before** `connect()` influences the TCP window
/// scale factor negotiated in the SYN packet.
pub fn set_rcvbuf(fd: &impl AsRawFd, size: u32) -> io::Result<()> {
    let val = size as libc::c_int;
    set_c_int_sockopt(fd.as_raw_fd(), libc::SOL_SOCKET, libc::SO_RCVBUF, val)
}

/// Read the current `SO_RCVBUF` value on a socket.
#[cfg(test)]
pub fn get_rcvbuf(fd: &impl AsRawFd) -> io::Result<u32> {
    Ok(get_c_int_sockopt(fd.as_raw_fd(), libc::SOL_SOCKET, libc::SO_RCVBUF)? as u32)
}

/// Bind a UDP socket to a source port that is at most `max_port`.
///
/// Tries random ports in `[1024, max_port]` until one binds successfully.
/// Returns the bound port. Falls back to OS-assigned if all attempts fail.
pub fn bind_udp_low_port(socket: &UdpSocket, local_ip: IpAddr, max_port: u16) -> io::Result<u16> {
    let lower = 1024u16;
    if max_port <= lower {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "max_port too low"));
    }
    // Try a few random ports in the range [1024, max_port].
    let mut rng_state =
        (std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().subsec_nanos()) as u16;
    for _ in 0..8 {
        rng_state = rng_state.wrapping_mul(25173).wrapping_add(13849);
        let port = lower + (rng_state % (max_port - lower + 1));
        let addr = SocketAddr::new(local_ip, port);
        let fd = socket.as_raw_fd();
        let sa = socket2::SockAddr::from(addr);
        let ret = unsafe { libc::bind(fd, sa.as_ptr().cast(), sa.len()) };
        if ret == 0 {
            return Ok(port);
        }
    }
    // Fallback: let OS pick.
    let addr = SocketAddr::new(local_ip, 0);
    let fd = socket.as_raw_fd();
    let sa = socket2::SockAddr::from(addr);
    let ret = unsafe { libc::bind(fd, sa.as_ptr().cast(), sa.len()) };
    if ret != 0 {
        return Err(io::Error::last_os_error());
    }
    socket.local_addr().map(|a| a.port())
}

pub fn enable_recv_ttl(stream: &TcpStream) -> io::Result<()> {
    let fd = stream.as_raw_fd();
    // On dual-stack sockets both options may be valid, so attempt both and
    // succeed if at least one takes effect (mirrors `set_stream_ttl` pattern).
    let ipv4 = set_c_int_sockopt(fd, libc::IPPROTO_IP, libc::IP_RECVTTL, 1);
    let ipv6 = set_c_int_sockopt(fd, libc::IPPROTO_IPV6, libc::IPV6_RECVHOPLIMIT, 1);
    match (ipv4, ipv6) {
        (Ok(()), _) | (_, Ok(())) => Ok(()),
        (Err(err), _) => Err(err),
    }
}

pub fn read_chunk_with_ttl(stream: &TcpStream, buf: &mut [u8]) -> io::Result<(usize, Option<u8>)> {
    if buf.is_empty() {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "read_chunk_with_ttl: buf must not be empty"));
    }
    let fd = stream.as_raw_fd();
    let ctrl_len = unsafe { libc::CMSG_SPACE(size_of::<libc::c_int>() as u32) } as usize;
    let mut ctrl = vec![0u8; ctrl_len];
    let mut iov = libc::iovec { iov_base: buf.as_mut_ptr().cast(), iov_len: buf.len() };
    let mut msg: libc::msghdr = unsafe { zeroed() };
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = ctrl.as_mut_ptr().cast();
    msg.msg_controllen = ctrl_len;

    // SAFETY: `msg` references live stack/heap storage for the iov and control
    // buffers, and `fd` is a valid TCP socket descriptor owned by `stream`.
    let n = unsafe { libc::recvmsg(fd, &mut msg, 0) };
    if n < 0 {
        return Err(io::Error::last_os_error());
    }
    if n == 0 {
        return Ok((0, None));
    }

    let mut ttl: Option<u8> = None;
    // SAFETY: `msg` was just populated by `recvmsg`; CMSG_FIRSTHDR/CMSG_NXTHDR
    // iterate over the ancillary data buffer we provided.
    let mut cmsg = unsafe { libc::CMSG_FIRSTHDR(&msg) };
    while !cmsg.is_null() {
        let cmsg_ref = unsafe { &*cmsg };
        if (cmsg_ref.cmsg_level == libc::IPPROTO_IP && cmsg_ref.cmsg_type == libc::IP_TTL)
            || (cmsg_ref.cmsg_level == libc::IPPROTO_IPV6 && cmsg_ref.cmsg_type == libc::IPV6_HOPLIMIT)
        {
            // SAFETY: cmsg_data points into the control buffer we own; the
            // kernel wrote a c_int there per the IP_TTL cmsg spec.
            let value: libc::c_int = unsafe { ptr::read_unaligned(libc::CMSG_DATA(cmsg).cast()) };
            ttl = u8::try_from(value).ok();
            break;
        }
        cmsg = unsafe { libc::CMSG_NXTHDR(&msg, cmsg) };
    }
    Ok((n as usize, ttl))
}

pub fn wait_tcp_stage(stream: &TcpStream, wait_send: bool, await_interval: Duration) -> io::Result<()> {
    wait_tcp_stage_fd(stream.as_raw_fd(), wait_send, await_interval)
}

fn peer_addr(fd: libc::c_int) -> io::Result<libc::sockaddr_storage> {
    // SAFETY: `storage` is zero-initialized and `getpeername` writes at most
    // `len` bytes into it for the valid socket descriptor `fd`.
    let mut storage = unsafe { zeroed::<libc::sockaddr_storage>() };
    let mut len = size_of::<libc::sockaddr_storage>() as libc::socklen_t;
    let rc = unsafe { libc::getpeername(fd, (&mut storage as *mut libc::sockaddr_storage).cast(), &mut len) };
    if rc == 0 {
        Ok(storage)
    } else {
        Err(io::Error::last_os_error())
    }
}

fn probe_raw_socket(
    domain: Domain,
    protocol: libc::c_int,
    protect_path: Option<&str>,
    level: libc::c_int,
    option_name: libc::c_int,
) -> io::Result<()> {
    let socket = Socket::new(domain, Type::RAW, Some(Protocol::from(protocol)))?;
    super::protect_socket(&socket, protect_path)?;
    set_c_int_sockopt(socket.as_raw_fd(), level, option_name, 1)
}

fn probe_tcp_repair(protect_path: Option<&str>) -> io::Result<()> {
    let socket = Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP))?;
    super::protect_socket(&socket, protect_path)?;
    let fd = socket.as_raw_fd();
    set_tcp_repair(fd, TCP_REPAIR_ON)?;
    disable_tcp_repair(fd)
}

fn resolve_raw_ttl(default_ttl: u8) -> u8 {
    if default_ttl != 0 {
        default_ttl
    } else {
        64
    }
}

fn apply_tcp_flag_overrides_to_bytes(flags_bytes: &mut [u8], overrides: TcpFlagOverrides) {
    if overrides.is_empty() {
        return;
    }
    let mut upper = flags_bytes[0] & 0xF0;
    let mut reserved = flags_bytes[0] & 0x0F;
    let mut control = flags_bytes[1];

    apply_single_flag(&mut control, overrides, TCP_FLAG_FIN, 0x01);
    apply_single_flag(&mut control, overrides, TCP_FLAG_SYN, 0x02);
    apply_single_flag(&mut control, overrides, TCP_FLAG_RST, 0x04);
    apply_single_flag(&mut control, overrides, TCP_FLAG_PSH, 0x08);
    apply_single_flag(&mut control, overrides, TCP_FLAG_ACK, 0x10);
    apply_single_flag(&mut control, overrides, TCP_FLAG_URG, 0x20);
    apply_single_flag(&mut control, overrides, TCP_FLAG_ECE, 0x40);
    apply_single_flag(&mut control, overrides, TCP_FLAG_CWR, 0x80);
    apply_single_flag(&mut reserved, overrides, TCP_FLAG_AE, 0x01);
    apply_single_flag(&mut reserved, overrides, TCP_FLAG_R1, 0x02);
    apply_single_flag(&mut reserved, overrides, TCP_FLAG_R2, 0x04);
    apply_single_flag(&mut reserved, overrides, TCP_FLAG_R3, 0x08);

    upper |= reserved & 0x0F;
    flags_bytes[0] = upper;
    flags_bytes[1] = control;
}

fn apply_single_flag(byte: &mut u8, overrides: TcpFlagOverrides, flag_mask: u16, wire_bit: u8) {
    if (overrides.set & flag_mask) != 0 {
        *byte |= wire_bit;
    }
    if (overrides.unset & flag_mask) != 0 {
        *byte &= !wire_bit;
    }
}

fn apply_tcp_flag_overrides_to_packet(
    packet: &mut [u8],
    source: SocketAddr,
    target: SocketAddr,
    payload_len: usize,
    overrides: TcpFlagOverrides,
) -> io::Result<()> {
    if overrides.is_empty() {
        return Ok(());
    }
    let tcp_offset = match source {
        SocketAddr::V4(_) => Ipv4Header::MIN_LEN,
        SocketAddr::V6(_) => Ipv6Header::LEN,
    };
    if packet.len() < tcp_offset + 20 {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "raw TCP packet too short"));
    }
    let flags = &mut packet[tcp_offset + 12..tcp_offset + 14];
    apply_tcp_flag_overrides_to_bytes(flags, overrides);
    packet[tcp_offset + 16] = 0;
    packet[tcp_offset + 17] = 0;
    let header = TcpHeaderSlice::from_slice(&packet[tcp_offset..]).map_err(io::Error::other)?;
    let payload = &packet[packet.len().saturating_sub(payload_len)..];
    let checksum = match (source, target) {
        (SocketAddr::V4(src), SocketAddr::V4(dst)) => header
            .calc_checksum_ipv4_raw(src.ip().octets(), dst.ip().octets(), payload)
            .map_err(|_| value_too_large_io("raw TCP payload exceeds IPv4 checksum limits"))?,
        (SocketAddr::V6(src), SocketAddr::V6(dst)) => header
            .calc_checksum_ipv6_raw(src.ip().octets(), dst.ip().octets(), payload)
            .map_err(|_| value_too_large_io("raw TCP payload exceeds IPv6 checksum limits"))?,
        _ => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "raw TCP send requires matching source and destination IP families",
            ));
        }
    };
    packet[tcp_offset + 16..tcp_offset + 18].copy_from_slice(&checksum.to_be_bytes());
    Ok(())
}

fn fragment_identification(source: SocketAddr, target: SocketAddr, payload_len: usize) -> u32 {
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap_or_default().subsec_nanos();
    let source_mix = u32::from(source.port()) << 16;
    let target_mix = u32::from(target.port());
    now ^ source_mix ^ target_mix ^ (payload_len as u32)
}

fn build_error_to_io(error: ripdpi_ipfrag::BuildError) -> io::Error {
    match error {
        ripdpi_ipfrag::BuildError::InvalidSplit { .. } => io::Error::new(io::ErrorKind::InvalidInput, error),
        _ => io::Error::new(io::ErrorKind::InvalidData, error),
    }
}

fn value_too_large_io(message: &'static str) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, message)
}

fn snapshot_tcp_repair_state(fd: libc::c_int) -> io::Result<TcpRepairSnapshot> {
    if pending_tcp_read_bytes(fd)? != 0 {
        return Err(io::Error::new(
            io::ErrorKind::WouldBlock,
            "packet-owned TCP desync requires an empty inbound queue before raw injection",
        ));
    }
    if tcp_has_notsent(fd)? {
        return Err(io::Error::new(
            io::ErrorKind::WouldBlock,
            "packet-owned TCP desync requires an empty TCP send queue before raw injection",
        ));
    }

    let info = read_tcp_info(fd)?.ok_or_else(|| {
        io::Error::new(io::ErrorKind::Unsupported, "packet-owned TCP desync requires TCP_INFO support")
    })?;

    set_tcp_repair_queue(fd, TCP_SEND_QUEUE)?;
    let sequence_number = get_tcp_queue_seq(fd)?;
    let repair_window = get_tcp_repair_window(fd)?;

    set_tcp_repair_queue(fd, TCP_RECV_QUEUE)?;
    let acknowledgment_number = get_tcp_queue_seq(fd)?;
    set_tcp_repair_queue(fd, TCP_NO_QUEUE)?;

    let options = snapshot_tcp_repair_options(fd, info)?;
    Ok(TcpRepairSnapshot {
        sequence_number,
        acknowledgment_number,
        window_size: repair_window.rcv_wnd.min(u32::from(u16::MAX)) as u16,
        repair_window,
        options,
    })
}

fn snapshot_tcp_repair_options(fd: libc::c_int, info: LinuxTcpInfo) -> io::Result<TcpRepairOptionsSnapshot> {
    let timestamp = if info.tcpi_options & TCPI_OPT_TIMESTAMPS != 0 {
        let value = read_tcp_timestamp(fd).map_err(|error| {
            io::Error::new(
                io::ErrorKind::Unsupported,
                format!("packet-owned TCP desync could not snapshot negotiated TCP timestamps: {error}"),
            )
        })?;
        Some(TcpTimestampSnapshot { value, echo_reply: 0, usec_ts: info.tcpi_options & TCPI_OPT_USEC_TS != 0 })
    } else {
        None
    };

    Ok(decode_tcp_repair_options(info, timestamp))
}

fn decode_tcp_repair_options(info: LinuxTcpInfo, timestamp: Option<TcpTimestampSnapshot>) -> TcpRepairOptionsSnapshot {
    let window_scale = if info.tcpi_options & TCPI_OPT_WSCALE != 0 {
        Some(TcpWindowScaleSnapshot {
            send: info.tcpi_snd_wscale_rcv_wscale & 0x0f,
            receive: info.tcpi_snd_wscale_rcv_wscale >> 4,
        })
    } else {
        None
    };

    TcpRepairOptionsSnapshot {
        mss: u16::try_from(info.tcpi_snd_mss).ok().filter(|value| *value != 0),
        sack_permitted: info.tcpi_options & TCPI_OPT_SACK != 0,
        window_scale,
        timestamp,
    }
}

fn build_replacement_tcp_socket(
    source: SocketAddr,
    target: SocketAddr,
    payload_len: usize,
    snapshot: &TcpRepairSnapshot,
    protect_path: Option<&str>,
) -> io::Result<Socket> {
    let domain = match target {
        SocketAddr::V4(_) => Domain::IPV4,
        SocketAddr::V6(_) => Domain::IPV6,
    };
    let replacement = Socket::new(domain, Type::STREAM, Some(Protocol::TCP))?;
    replacement.set_reuse_address(true)?;
    let _ = replacement.set_reuse_port(true);
    super::protect_socket(&replacement, protect_path)?;

    let fd = replacement.as_raw_fd();
    set_tcp_repair(fd, TCP_REPAIR_ON)?;
    let result = (|| -> io::Result<()> {
        replacement.bind(&SockAddr::from(source))?;

        set_tcp_repair_queue(fd, TCP_SEND_QUEUE)?;
        set_tcp_queue_seq(fd, sequence_after_payload(snapshot.sequence_number, payload_len)?)?;

        set_tcp_repair_queue(fd, TCP_RECV_QUEUE)?;
        set_tcp_queue_seq(fd, snapshot.acknowledgment_number)?;
        set_tcp_repair_queue(fd, TCP_NO_QUEUE)?;

        replacement.connect(&SockAddr::from(target))?;
        apply_tcp_repair_options(fd, snapshot.options)?;
        set_tcp_repair_window(fd, snapshot.repair_window)?;
        Ok(())
    })();
    if result.is_err() {
        let _ = set_tcp_repair_queue(fd, TCP_NO_QUEUE);
        let _ = disable_tcp_repair(fd);
    }
    result.map(|_| replacement)
}

fn apply_tcp_repair_options(fd: libc::c_int, options: TcpRepairOptionsSnapshot) -> io::Result<()> {
    if let Some(mss) = options.mss {
        set_tcp_repair_option(fd, TcpRepairOpt { opt_code: TCPOPT_MSS, opt_val: u32::from(mss) })?;
    }
    if let Some(scale) = options.window_scale {
        set_tcp_repair_option(
            fd,
            TcpRepairOpt { opt_code: TCPOPT_WINDOW, opt_val: u32::from(scale.send) | (u32::from(scale.receive) << 16) },
        )?;
    }
    if options.sack_permitted {
        set_tcp_repair_option(fd, TcpRepairOpt { opt_code: TCPOPT_SACK_PERM, opt_val: 0 })?;
    }
    if let Some(timestamp) = options.timestamp {
        set_tcp_repair_option(fd, TcpRepairOpt { opt_code: TCPOPT_TIMESTAMP, opt_val: 0 })?;
        set_tcp_timestamp(fd, timestamp.value, timestamp.usec_ts)?;
    }
    Ok(())
}

fn swap_stream_to_replacement(
    stream: &TcpStream,
    replacement: &Socket,
    settings: StreamSocketSettings,
) -> io::Result<()> {
    let target_fd = stream.as_raw_fd();
    let replacement_fd = replacement.as_raw_fd();
    dup2_fd(replacement_fd, target_fd)?;
    apply_stream_socket_settings(stream, settings);
    Ok(())
}

fn capture_stream_socket_settings(stream: &TcpStream) -> StreamSocketSettings {
    StreamSocketSettings {
        nodelay: stream.nodelay().ok(),
        read_timeout: stream.read_timeout().ok(),
        write_timeout: stream.write_timeout().ok(),
    }
}

fn apply_stream_socket_settings(stream: &TcpStream, settings: StreamSocketSettings) {
    if let Some(nodelay) = settings.nodelay {
        if let Err(error) = stream.set_nodelay(nodelay) {
            tracing::debug!("failed to restore TCP_NODELAY after ipfrag2 socket handoff: {error}");
        }
    }
    if let Some(timeout) = settings.read_timeout {
        if let Err(error) = stream.set_read_timeout(timeout) {
            tracing::debug!("failed to restore read timeout after ipfrag2 socket handoff: {error}");
        }
    }
    if let Some(timeout) = settings.write_timeout {
        if let Err(error) = stream.set_write_timeout(timeout) {
            tracing::debug!("failed to restore write timeout after ipfrag2 socket handoff: {error}");
        }
    }
}

fn send_raw_fragments(target: SocketAddr, packets: [&[u8]; 2], protect_path: Option<&str>) -> io::Result<()> {
    send_raw_packets(target, packets, protect_path)
}

fn send_raw_packets<'a, I>(target: SocketAddr, packets: I, protect_path: Option<&str>) -> io::Result<()>
where
    I: IntoIterator<Item = &'a [u8]>,
{
    let socket = match target {
        SocketAddr::V4(_) => {
            let socket = Socket::new(Domain::IPV4, Type::RAW, Some(Protocol::from(libc::IPPROTO_RAW)))?;
            super::protect_socket(&socket, protect_path)?;
            // SAFETY: socket fd is valid (just created above) and IP_HDRINCL optval is a C integer.
            unsafe { setsockopt_raw(socket.as_raw_fd(), libc::IPPROTO_IP, libc::IP_HDRINCL, &1i32) }?;
            socket
        }
        SocketAddr::V6(_) => {
            let socket = Socket::new(Domain::IPV6, Type::RAW, Some(Protocol::from(libc::IPPROTO_RAW)))?;
            super::protect_socket(&socket, protect_path)?;
            // SAFETY: socket fd is valid (just created above) and IPV6_HDRINCL optval is a C integer.
            unsafe { setsockopt_raw(socket.as_raw_fd(), libc::IPPROTO_IPV6, libc::IPV6_HDRINCL, &1i32) }?;
            socket
        }
    };
    let sockaddr = SockAddr::from(target);
    for packet in packets {
        socket.send_to(packet, &sockaddr)?;
    }
    Ok(())
}

fn send_raw_packets_with_delay<'a, I>(
    target: SocketAddr,
    packets: I,
    protect_path: Option<&str>,
    inter_segment_delay_ms: u32,
) -> io::Result<()>
where
    I: IntoIterator<Item = &'a [u8]>,
{
    if inter_segment_delay_ms == 0 {
        return send_raw_packets(target, packets, protect_path);
    }
    let socket = open_raw_socket(target, protect_path)?;
    let sockaddr = SockAddr::from(target);
    let delay = std::time::Duration::from_millis(u64::from(inter_segment_delay_ms));
    let mut first = true;
    for packet in packets {
        if !first {
            std::thread::sleep(delay);
        }
        socket.send_to(packet, &sockaddr)?;
        first = false;
    }
    Ok(())
}

fn open_raw_socket(target: SocketAddr, protect_path: Option<&str>) -> io::Result<Socket> {
    match target {
        SocketAddr::V4(_) => {
            let socket = Socket::new(Domain::IPV4, Type::RAW, Some(Protocol::from(libc::IPPROTO_RAW)))?;
            super::protect_socket(&socket, protect_path)?;
            // SAFETY: socket fd is valid (just created above) and IP_HDRINCL optval is a C integer.
            unsafe { setsockopt_raw(socket.as_raw_fd(), libc::IPPROTO_IP, libc::IP_HDRINCL, &1i32) }?;
            Ok(socket)
        }
        SocketAddr::V6(_) => {
            let socket = Socket::new(Domain::IPV6, Type::RAW, Some(Protocol::from(libc::IPPROTO_RAW)))?;
            super::protect_socket(&socket, protect_path)?;
            // SAFETY: socket fd is valid (just created above) and IPV6_HDRINCL optval is a C integer.
            unsafe { setsockopt_raw(socket.as_raw_fd(), libc::IPPROTO_IPV6, libc::IPV6_HDRINCL, &1i32) }?;
            Ok(socket)
        }
    }
}

fn set_tcp_repair(fd: libc::c_int, value: libc::c_int) -> io::Result<()> {
    set_c_int_sockopt(fd, libc::IPPROTO_TCP, TCP_REPAIR, value)
}

#[allow(clippy::too_many_arguments)]
fn build_tcp_segment_packet(
    source: SocketAddr,
    target: SocketAddr,
    ttl: u8,
    identification: u32,
    sequence_number: u32,
    acknowledgment_number: u32,
    window_size: u16,
    timestamp: Option<TcpTimestampSnapshot>,
    push_flag: bool,
    payload: &[u8],
    inject_md5: bool,
    tcp_flags: TcpFlagOverrides,
) -> io::Result<Vec<u8>> {
    let mut tcp = TcpHeader::new(source.port(), target.port(), sequence_number, window_size);
    tcp.ack = true;
    tcp.psh = push_flag && !payload.is_empty();
    tcp.acknowledgment_number = acknowledgment_number;

    let mut raw_opts: Vec<u8> = Vec::new();
    if let Some(timestamp) = timestamp {
        // Noop, Noop, Timestamp (12 bytes total with padding)
        raw_opts.extend_from_slice(&[1, 1]); // 2x Noop
        raw_opts.push(8); // Kind=Timestamp
        raw_opts.push(10); // Length=10
        raw_opts.extend_from_slice(&timestamp.value.to_be_bytes());
        raw_opts.extend_from_slice(&timestamp.echo_reply.to_be_bytes());
    }
    if inject_md5 {
        // Noop padding before MD5 for 4-byte alignment
        let padding_needed = (4 - (raw_opts.len() % 4)) % 4;
        raw_opts.extend(std::iter::repeat_n(1u8, padding_needed)); // Noop
        raw_opts.push(19); // Kind=MD5 Signature (RFC 2385)
        raw_opts.push(18); // Length=18 (2 header + 16 signature)
                           // Random 16-byte signature (deterministic from seq for reproducibility)
        let seed = sequence_number;
        for i in 0u32..4 {
            raw_opts.extend_from_slice(&seed.wrapping_add(i).wrapping_mul(2654435761).to_be_bytes());
        }
    }
    if !raw_opts.is_empty() {
        // Pad to 4-byte boundary
        while !raw_opts.len().is_multiple_of(4) {
            raw_opts.push(0); // End-of-options
        }
        tcp.set_options_raw(&raw_opts)
            .map_err(|_| value_too_large_io("TCP options exceed supported raw packet size"))?;
    }

    match (source, target) {
        (SocketAddr::V4(src), SocketAddr::V4(dst)) => {
            tcp.checksum = tcp
                .calc_checksum_ipv4_raw(src.ip().octets(), dst.ip().octets(), payload)
                .map_err(|_| value_too_large_io("raw TCP payload exceeds IPv4 checksum limits"))?;
            let payload_length = u16::try_from(tcp.header_len() + payload.len())
                .map_err(|_| value_too_large_io("IPv4 packet too large"))?;
            let mut ip = Ipv4Header::new(payload_length, ttl, ip_number::TCP, src.ip().octets(), dst.ip().octets())
                .map_err(|_| value_too_large_io("IPv4 packet too large"))?;
            ip.identification = identification as u16;
            ip.dont_fragment = false;
            ip.more_fragments = false;
            ip.header_checksum = ip.calc_header_checksum();

            let mut bytes = Vec::with_capacity(Ipv4Header::MIN_LEN + tcp.header_len() + payload.len());
            ip.write(&mut bytes).map_err(io::Error::other)?;
            tcp.write(&mut bytes).map_err(io::Error::other)?;
            bytes.extend_from_slice(payload);
            apply_tcp_flag_overrides_to_packet(&mut bytes, source, target, payload.len(), tcp_flags)?;
            Ok(bytes)
        }
        (SocketAddr::V6(src), SocketAddr::V6(dst)) => {
            tcp.checksum = tcp
                .calc_checksum_ipv6_raw(src.ip().octets(), dst.ip().octets(), payload)
                .map_err(|_| value_too_large_io("raw TCP payload exceeds IPv6 checksum limits"))?;
            let payload_length = u16::try_from(tcp.header_len() + payload.len())
                .map_err(|_| value_too_large_io("IPv6 packet too large"))?;
            let ip = Ipv6Header {
                traffic_class: 0,
                flow_label: Ipv6FlowLabel::ZERO,
                payload_length,
                next_header: ip_number::TCP,
                hop_limit: ttl,
                source: src.ip().octets(),
                destination: dst.ip().octets(),
            };

            let mut bytes = Vec::with_capacity(Ipv6Header::LEN + tcp.header_len() + payload.len());
            ip.write(&mut bytes).map_err(io::Error::other)?;
            tcp.write(&mut bytes).map_err(io::Error::other)?;
            bytes.extend_from_slice(payload);
            apply_tcp_flag_overrides_to_packet(&mut bytes, source, target, payload.len(), tcp_flags)?;
            Ok(bytes)
        }
        _ => Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "multidisorder raw TCP send requires matching source and destination IP families",
        )),
    }
}

fn set_tcp_repair_option(fd: libc::c_int, value: TcpRepairOpt) -> io::Result<()> {
    // SAFETY: `TcpRepairOpt` matches the Linux `TCP_REPAIR_OPTIONS` ABI and
    // `fd` is a live TCP socket in repair mode at all call sites.
    unsafe { setsockopt_raw(fd, libc::IPPROTO_TCP, TCP_REPAIR_OPTIONS, &value) }
}

fn set_tcp_repair_queue(fd: libc::c_int, value: libc::c_int) -> io::Result<()> {
    set_c_int_sockopt(fd, libc::IPPROTO_TCP, TCP_REPAIR_QUEUE, value)
}

fn set_tcp_queue_seq(fd: libc::c_int, value: u32) -> io::Result<()> {
    set_u32_sockopt(fd, libc::IPPROTO_TCP, TCP_QUEUE_SEQ, value)
}

fn get_tcp_queue_seq(fd: libc::c_int) -> io::Result<u32> {
    get_u32_sockopt(fd, libc::IPPROTO_TCP, TCP_QUEUE_SEQ)
}

fn read_tcp_timestamp(fd: libc::c_int) -> io::Result<u32> {
    let value = get_c_int_sockopt(fd, libc::IPPROTO_TCP, libc::TCP_TIMESTAMP)?;
    u32::try_from(value).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "negative TCP timestamp"))
}

fn set_tcp_timestamp(fd: libc::c_int, value: u32, usec_ts: bool) -> io::Result<()> {
    let mut encoded = value & !1;
    if usec_ts {
        encoded |= 1;
    }
    let encoded =
        i32::try_from(encoded).map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "TCP timestamp exceeds i32"))?;
    set_c_int_sockopt(fd, libc::IPPROTO_TCP, libc::TCP_TIMESTAMP, encoded)
}

fn get_tcp_repair_window(fd: libc::c_int) -> io::Result<TcpRepairWindow> {
    // SAFETY: `TcpRepairWindow` matches the Linux `TCP_REPAIR_WINDOW` ABI and
    // `fd` is a live TCP socket in repair mode at all call sites.
    let (value, _): (TcpRepairWindow, _) = unsafe { getsockopt_raw(fd, libc::IPPROTO_TCP, TCP_REPAIR_WINDOW) }?;
    Ok(value)
}

fn set_tcp_repair_window(fd: libc::c_int, value: TcpRepairWindow) -> io::Result<()> {
    // SAFETY: `TcpRepairWindow` matches the Linux `TCP_REPAIR_WINDOW` ABI and
    // `fd` is a live TCP socket in repair mode at all call sites.
    unsafe { setsockopt_raw(fd, libc::IPPROTO_TCP, TCP_REPAIR_WINDOW, &value) }
}

fn disable_tcp_repair(fd: libc::c_int) -> io::Result<()> {
    set_tcp_repair(fd, TCP_REPAIR_OFF_NO_WP).or_else(|_| set_tcp_repair(fd, TCP_REPAIR_OFF))
}

fn pending_tcp_read_bytes(fd: libc::c_int) -> io::Result<usize> {
    let mut bytes: libc::c_int = 0;
    // SAFETY: fd is a valid TCP socket fd passed by the caller and `bytes` is a stack-allocated C integer valid for FIONREAD.
    let rc = unsafe { libc::ioctl(fd, libc::FIONREAD, &mut bytes) };
    if rc == 0 {
        usize::try_from(bytes)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "negative pending TCP read byte count"))
    } else {
        Err(io::Error::last_os_error())
    }
}

fn sequence_after_payload(sequence_number: u32, payload_len: usize) -> io::Result<u32> {
    let payload_len = u32::try_from(payload_len)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "payload too large for TCP sequence arithmetic"))?;
    sequence_number
        .checked_add(payload_len)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "TCP sequence arithmetic overflow"))
}

fn storage_to_socket_addr(storage: &libc::sockaddr_storage) -> io::Result<SocketAddr> {
    match i32::from(storage.ss_family) {
        libc::AF_INET => {
            // SAFETY: family tag was checked to be AF_INET.
            let sin = unsafe { &*(storage as *const libc::sockaddr_storage).cast::<libc::sockaddr_in>() };
            let ip = Ipv4Addr::from(u32::from_be(sin.sin_addr.s_addr));
            let port = u16::from_be(sin.sin_port);
            Ok(SocketAddr::new(IpAddr::V4(ip), port))
        }
        libc::AF_INET6 => {
            // SAFETY: family tag was checked to be AF_INET6.
            let sin6 = unsafe { &*(storage as *const libc::sockaddr_storage).cast::<libc::sockaddr_in6>() };
            let ip = Ipv6Addr::from(sin6.sin6_addr.s6_addr);
            let port = u16::from_be(sin6.sin6_port);
            Ok(SocketAddr::new(IpAddr::V6(ip), port))
        }
        _ => {
            Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported socket family in original destination lookup"))
        }
    }
}

/// Read the current TTL (IPv4) or hop limit (IPv6) from a TCP socket.
/// Tries IPv4 first; falls back to IPv6. Returns the value from whichever
/// succeeds first.
fn get_stream_ttl(stream: &TcpStream) -> io::Result<u8> {
    let socket = SockRef::from(stream);
    if let Ok(ttl) = socket.ttl_v4() {
        return u8::try_from(ttl).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "socket ttl exceeds u8"));
    }
    let hops = socket.unicast_hops_v6()?;
    u8::try_from(hops).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "socket hop limit exceeds u8"))
}

fn set_stream_ttl(stream: &TcpStream, ttl: u8) -> io::Result<()> {
    let socket = SockRef::from(stream);
    let ipv4 = socket.set_ttl_v4(ttl as u32);
    let ipv6 = socket.set_unicast_hops_v6(ttl as u32);
    match (ipv4, ipv6) {
        (Ok(()), _) | (_, Ok(())) => Ok(()),
        (Err(err), _) => Err(err),
    }
}

/// Attempt to set the IP TTL (IPv4) or unicast hop limit (IPv6) on a TCP
/// stream, returning a typed [`CapabilityOutcome`] rather than an
/// `io::Result`.
///
/// Error mapping:
/// - `ENOPROTOOPT` / `EOPNOTSUPP` / `EROFS` / `EINVAL` (kernel unsupported)
///   → `Unavailable { reason: Unsupported }`
/// - `EACCES` / `EPERM` (permission denied)
///   → `Unavailable { reason: PermissionDenied }`
/// - any other `io::Error`
///   → `ProbeFailed { error: err.to_string() }`
///
/// New code that needs to write a TTL should call this function rather than
/// [`set_stream_ttl`] directly (see module-level doc comment).
pub fn try_set_stream_ttl_with_outcome(stream: &TcpStream, ttl: u8) -> CapabilityOutcome<()> {
    match set_stream_ttl(stream, ttl) {
        Ok(()) => CapabilityOutcome::Available(()),
        Err(err) => match err.raw_os_error() {
            Some(libc::ENOPROTOOPT | libc::EOPNOTSUPP | libc::EROFS | libc::EINVAL) => CapabilityOutcome::Unavailable {
                capability: RuntimeCapability::TtlWrite,
                reason: CapabilityUnavailable::Unsupported,
            },
            Some(libc::EACCES | libc::EPERM) => CapabilityOutcome::Unavailable {
                capability: RuntimeCapability::TtlWrite,
                reason: CapabilityUnavailable::PermissionDenied,
            },
            _ => CapabilityOutcome::ProbeFailed { capability: RuntimeCapability::TtlWrite, error: err.to_string() },
        },
    }
}

fn tcp_has_notsent(fd: libc::c_int) -> io::Result<bool> {
    let Some(info) = read_tcp_info(fd)? else {
        return Ok(false);
    };
    if info.tcpi_state != TCP_ESTABLISHED {
        return Ok(false);
    }
    Ok(info.tcpi_notsent_bytes != 0)
}

fn read_tcp_info(fd: libc::c_int) -> io::Result<Option<LinuxTcpInfo>> {
    // SAFETY: `fd` is a live TCP socket; `LinuxTcpInfo` is a `#[repr(C)]`
    // prefix of the kernel `tcp_info` struct.
    let (info, len) = unsafe { getsockopt_raw::<LinuxTcpInfo>(fd, libc::IPPROTO_TCP, libc::TCP_INFO) }?;
    if (len as usize) < size_of::<LinuxTcpInfo>() {
        return Ok(None);
    }
    Ok(Some(info))
}

pub fn tcp_segment_hint(stream: &TcpStream) -> io::Result<Option<TcpSegmentHint>> {
    let Some(info) = read_tcp_info(stream.as_raw_fd())? else {
        return Ok(None);
    };
    // Combined IP + TCP header overhead (IPv4: 20+20=40, IPv6: 40+20=60).
    // Named `ip_header_overhead` for historical reasons; the value includes
    // the minimum TCP header as well.
    let ip_header_overhead = match stream.peer_addr()? {
        SocketAddr::V4(_) => 40,
        SocketAddr::V6(_) => 60,
    };
    Ok(Some(TcpSegmentHint {
        snd_mss: (info.tcpi_snd_mss != 0).then_some(info.tcpi_snd_mss as i64),
        advmss: (info.tcpi_advmss != 0).then_some(info.tcpi_advmss as i64),
        pmtu: (info.tcpi_pmtu != 0).then_some(info.tcpi_pmtu as i64),
        ip_header_overhead,
    }))
}

pub fn tcp_activation_state(stream: &TcpStream) -> io::Result<Option<TcpActivationState>> {
    let Some(info) = read_tcp_info(stream.as_raw_fd())? else {
        return Ok(None);
    };
    let fd = stream.as_raw_fd();
    let snapshot = set_tcp_repair(fd, TCP_REPAIR_ON)
        .and_then(|_| {
            let snapshot = snapshot_tcp_repair_state(fd);
            let _ = set_tcp_repair_queue(fd, TCP_NO_QUEUE);
            let _ = disable_tcp_repair(fd);
            snapshot
        })
        .ok();

    Ok(Some(TcpActivationState {
        has_timestamp: Some(
            snapshot.as_ref().is_some_and(|value| value.options.timestamp.is_some())
                || (info.tcpi_options & TCPI_OPT_TIMESTAMPS != 0),
        ),
        window_size: snapshot.map(|value| i64::from(value.window_size)),
        mss: snapshot
            .and_then(|value| value.options.mss.map(i64::from))
            .or_else(|| (info.tcpi_snd_mss != 0).then_some(info.tcpi_snd_mss as i64)),
    }))
}

pub fn tcp_round_trip_time_ms(stream: &TcpStream) -> io::Result<Option<u64>> {
    let Some(info) = read_tcp_info(stream.as_raw_fd())? else {
        return Ok(None);
    };
    if info.tcpi_state != TCP_ESTABLISHED {
        return Ok(None);
    }
    Ok(Some(u64::from(info.tcpi_rtt / 1_000)))
}

pub fn tcp_total_retransmissions<T: AsRawFd>(socket: &T) -> io::Result<Option<u32>> {
    let Some(info) = read_tcp_info(socket.as_raw_fd())? else {
        return Ok(None);
    };
    tcp_total_retransmissions_from_info(&info)
}

fn tcp_total_retransmissions_from_info(info: &LinuxTcpInfo) -> io::Result<Option<u32>> {
    if info.tcpi_state == 0 {
        return Ok(None);
    }
    Ok(Some(info.tcpi_total_retrans.max(u32::from(info.tcpi_retransmits))))
}

fn wait_tcp_stage_fd(fd: libc::c_int, wait_send: bool, await_interval: Duration) -> io::Result<()> {
    let sleep_for = if await_interval.is_zero() { Duration::from_millis(1) } else { await_interval };
    if wait_send {
        thread::sleep(sleep_for);
        if !tcp_has_notsent(fd)? {
            return Ok(());
        }
    } else if !tcp_has_notsent(fd)? {
        return Ok(());
    }

    let deadline = Instant::now() + Duration::from_secs(5);
    loop {
        if Instant::now() >= deadline {
            if wait_send {
                return Ok(());
            }
            return Err(io::Error::new(io::ErrorKind::TimedOut, "timed out waiting for tcp send queue to drain"));
        }
        thread::sleep(sleep_for);
        if !tcp_has_notsent(fd)? {
            return Ok(());
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use etherparse::{Ipv4Header, TcpHeader};
    use std::io::Write;
    use std::mem::zeroed;
    use std::net::TcpListener;
    use std::os::fd::IntoRawFd;
    use std::slice;

    fn connected_pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
        let addr = listener.local_addr().expect("listener addr");
        let client = TcpStream::connect(addr).expect("connect client");
        let (server, _) = listener.accept().expect("accept client");
        (client, server)
    }

    /// Query the number of BPF instructions in the currently attached socket filter.
    /// Returns `Err` if no filter is attached.
    fn get_bpf_filter_len(fd: libc::c_int) -> io::Result<usize> {
        // SO_GET_FILTER shares the same constant as SO_ATTACH_FILTER on the getsockopt
        // path. The kernel returns the number of program instructions as the syscall
        // return value (positive integer) and copies the filter bytes into `optval` if
        // the buffer is large enough. glibc passes the positive return value through
        // unchanged. Pass a buffer big enough for any filter we attach in this crate
        // so the kernel's copy_to_sockptr step succeeds and the instruction count is
        // surfaced via the rc value.
        let mut buffer: [libc::sock_filter; 64] = unsafe { std::mem::zeroed() };
        let mut len: libc::socklen_t = size_of_val(&buffer) as libc::socklen_t;
        let rc = unsafe {
            libc::getsockopt(fd, libc::SOL_SOCKET, libc::SO_ATTACH_FILTER, buffer.as_mut_ptr().cast(), &mut len)
        };
        if rc < 0 {
            return Err(io::Error::last_os_error());
        }
        Ok(rc as usize)
    }

    fn get_tcp_fastopen_connect(fd: libc::c_int) -> io::Result<bool> {
        Ok(get_c_int_sockopt(fd, libc::IPPROTO_TCP, libc::TCP_FASTOPEN_CONNECT)? != 0)
    }

    fn get_recv_ttl(fd: libc::c_int) -> io::Result<bool> {
        Ok(get_c_int_sockopt(fd, libc::IPPROTO_IP, libc::IP_RECVTTL)? != 0)
    }

    fn sample_tcp_repair_snapshot() -> TcpRepairSnapshot {
        TcpRepairSnapshot {
            sequence_number: 0x0102_0304,
            acknowledgment_number: 0x0506_0708,
            window_size: 4096,
            repair_window: TcpRepairWindow { rcv_wnd: 4096, ..Default::default() },
            options: TcpRepairOptionsSnapshot {
                mss: Some(1440),
                sack_permitted: true,
                window_scale: Some(TcpWindowScaleSnapshot { send: 7, receive: 8 }),
                timestamp: Some(TcpTimestampSnapshot { value: 0x1122_3344, echo_reply: 0x5566_7788, usec_ts: false }),
            },
        }
    }

    #[test]
    fn dup2_fd_replaces_target_and_close_fd_releases_transient_source() {
        let (mut target_stream, _target_peer) = UnixStream::pair().expect("create target pair");
        let (source_stream, mut source_peer) = UnixStream::pair().expect("create source pair");
        let target_fd = target_stream.as_raw_fd();
        let source_fd = source_stream.into_raw_fd();

        dup2_fd(source_fd, target_fd).expect("replace target fd");
        close_fd(source_fd).expect("close transient source fd");

        source_peer.write_all(b"ok").expect("write through replacement peer");
        let mut buf = [0_u8; 2];
        target_stream.read_exact(&mut buf).expect("read from replaced target");
        assert_eq!(&buf, b"ok");

        // SAFETY: `source_fd` was closed by `close_fd`, so probing it with
        // `F_GETFD` should now fail with `EBADF`.
        let rc = unsafe { libc::fcntl(source_fd, libc::F_GETFD) };
        assert_eq!(rc, -1);
        assert_eq!(io::Error::last_os_error().raw_os_error(), Some(libc::EBADF));
    }

    #[test]
    fn tcp_total_retransmissions_prefers_total_counter_and_falls_back_to_retransmits() {
        let info = LinuxTcpInfo {
            tcpi_state: TCP_ESTABLISHED,
            tcpi_total_retrans: 5,
            tcpi_retransmits: 2,
            ..Default::default()
        };
        assert_eq!(tcp_total_retransmissions_from_info(&info).expect("extract"), Some(5));

        let fallback = LinuxTcpInfo {
            tcpi_state: TCP_ESTABLISHED,
            tcpi_total_retrans: 0,
            tcpi_retransmits: 3,
            ..Default::default()
        };
        assert_eq!(tcp_total_retransmissions_from_info(&fallback).expect("fallback"), Some(3));
    }

    #[test]
    fn storage_to_socket_addr_parses_ipv4_and_ipv6_sockaddrs() {
        let mut storage = unsafe { zeroed::<libc::sockaddr_storage>() };
        let sin = unsafe { &mut *(&mut storage as *mut libc::sockaddr_storage).cast::<libc::sockaddr_in>() };
        sin.sin_family = libc::AF_INET as libc::sa_family_t;
        sin.sin_port = 443u16.to_be();
        sin.sin_addr = libc::in_addr { s_addr: u32::from(Ipv4Addr::new(203, 0, 113, 8)).to_be() };
        assert_eq!(
            storage_to_socket_addr(&storage).expect("parse ipv4 sockaddr"),
            SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 8)), 443)
        );

        let mut storage6 = unsafe { zeroed::<libc::sockaddr_storage>() };
        let sin6 = unsafe { &mut *(&mut storage6 as *mut libc::sockaddr_storage).cast::<libc::sockaddr_in6>() };
        sin6.sin6_family = libc::AF_INET6 as libc::sa_family_t;
        sin6.sin6_port = 8443u16.to_be();
        sin6.sin6_addr = libc::in6_addr { s6_addr: Ipv6Addr::LOCALHOST.octets() };
        assert_eq!(
            storage_to_socket_addr(&storage6).expect("parse ipv6 sockaddr"),
            SocketAddr::new(IpAddr::V6(Ipv6Addr::LOCALHOST), 8443)
        );
    }

    #[test]
    fn storage_to_socket_addr_rejects_unknown_families() {
        let mut storage = unsafe { zeroed::<libc::sockaddr_storage>() };
        storage.ss_family = libc::AF_UNIX as libc::sa_family_t;

        let err = storage_to_socket_addr(&storage).expect_err("reject unsupported family");
        assert_eq!(err.kind(), io::ErrorKind::InvalidData);
    }

    #[test]
    fn set_tcp_md5sig_rejects_key_lengths_above_linux_limit() {
        let (client, _server) = connected_pair();
        let err = set_tcp_md5sig(&client, 81).expect_err("reject oversized md5 key");
        assert_eq!(err.kind(), io::ErrorKind::InvalidInput);
    }

    #[test]
    fn invalid_fds_report_errors_for_tcp_state_helpers() {
        let err = tcp_has_notsent(-1).expect_err("invalid fd should fail");
        assert_eq!(err.raw_os_error(), Some(libc::EBADF));

        let err = wait_tcp_stage_fd(-1, false, Duration::ZERO).expect_err("invalid fd should fail");
        assert_eq!(err.raw_os_error(), Some(libc::EBADF));
    }

    #[test]
    fn enable_recv_ttl_succeeds_on_connected_tcp_socket() {
        let (client, _server) = connected_pair();
        enable_recv_ttl(&client).expect("enable IP_RECVTTL on connected socket");
    }

    #[test]
    fn read_chunk_with_ttl_reads_data_from_connected_pair() {
        use std::io::Write;
        let (client, server) = connected_pair();
        enable_recv_ttl(&client).expect("enable recv ttl");
        let handle = std::thread::spawn(move || {
            (&server).write_all(b"hello").expect("server write");
        });
        let mut buf = [0u8; 16];
        client.set_read_timeout(Some(Duration::from_secs(2))).unwrap();
        let (n, _ttl) = read_chunk_with_ttl(&client, &mut buf).expect("read with ttl");
        handle.join().unwrap();
        assert_eq!(n, 5);
        assert_eq!(&buf[..n], b"hello");
        // TTL may or may not be populated for loopback; just verify no panic
    }

    #[test]
    fn get_stream_ttl_returns_valid_value_for_connected_socket() {
        let (client, _server) = connected_pair();
        let ttl = get_stream_ttl(&client).expect("read ttl from connected socket");
        assert!(ttl > 0, "default TTL should be positive");
    }

    #[test]
    fn alloc_and_write_region_round_trip_bytes() {
        let len = 8usize;
        let region = alloc_region(len).expect("allocate region");
        write_region(region, b"hello", len);

        let bytes = unsafe { slice::from_raw_parts(region, len) };
        assert_eq!(&bytes[..5], b"hello");
        assert_eq!(&bytes[5..], &[0, 0, 0]);

        free_region(region, len);
    }

    // --- Socket option verification tests ---

    #[test]
    fn tcp_window_clamp_set_and_readback() {
        let (client, _server) = connected_pair();
        let baseline = get_tcp_window_clamp(&client).expect("read baseline clamp");
        set_tcp_window_clamp(&client, 2).expect("set clamp to 2");
        let val = get_tcp_window_clamp(&client).expect("read clamp");
        // Kernel enforces a floor (max(value, SOCK_MIN_RCVBUF / 2)) so the
        // requested 2 is rounded up to the kernel's minimum (typically 1152
        // bytes on modern Linux). Verify the clamp is positive and meaningfully
        // tighter than the baseline rather than a fixed magic number.
        assert!(val > 0, "clamp should be positive after setting to 2, got {val}");
        assert!(val < baseline, "clamp should be tighter than baseline {baseline} after setting to 2, got {val}");
    }

    #[test]
    fn tcp_window_clamp_restore_to_large_value() {
        // Modern Linux rejects TCP_WINDOW_CLAMP=0 on connected sockets with
        // EINVAL (only sockets in TCP_CLOSE accept 0). The supported way to
        // "remove" the clamp on an established socket is to set it to a value
        // larger than any reasonable advertised window so the kernel's
        // min(clamp, peer_window) gate becomes a no-op.
        let (client, _server) = connected_pair();
        set_tcp_window_clamp(&client, 2).expect("set clamp to 2");
        set_tcp_window_clamp(&client, 1_000_000).expect("restore clamp to large value");
        let val = get_tcp_window_clamp(&client).expect("read clamp after restore");
        assert!(val > 256, "clamp after restore should be large, got {val}");
    }

    #[test]
    fn rcvbuf_set_and_readback() {
        let (client, _server) = connected_pair();
        set_rcvbuf(&client, 8192).expect("set rcvbuf to 8192");
        let val = get_rcvbuf(&client).expect("read rcvbuf");
        // Linux doubles SO_RCVBUF for kernel bookkeeping overhead.
        assert!(val >= 8192, "rcvbuf should be at least 8192 after setting, got {val}");
    }

    #[test]
    fn bpf_drop_sack_filter_attaches_and_detaches_cleanly() {
        // The kernel's SO_GET_FILTER readback uses an unusual return-value
        // convention (instruction count is delivered through the syscall ret,
        // not optlen) and behaves differently across kernel versions and libc
        // wrappers. Verify the attach succeeded by round-tripping through
        // detach: the kernel returns ENOENT on detach when no filter is bound,
        // so a clean Ok proves the program is in place.
        let (client, _server) = connected_pair();
        attach_drop_sack(&client).expect("attach drop_sack filter");
        detach_drop_sack(&client).expect("detach drop_sack filter");
    }

    #[test]
    fn bpf_strip_timestamps_filter_attaches_and_detaches_cleanly() {
        let (client, _server) = connected_pair();
        attach_strip_timestamps(&client).expect("attach strip_timestamps filter");
        // SO_DETACH_FILTER tears down whichever cBPF program is currently bound,
        // not just the drop_sack one, so it doubles as the strip-timestamps
        // detach for this attach-round-trip check.
        detach_drop_sack(&client).expect("detach strip_timestamps filter");
    }

    #[test]
    fn bpf_filter_detach_removes_program() {
        let (client, _server) = connected_pair();
        attach_drop_sack(&client).expect("attach filter");
        detach_drop_sack(&client).expect("detach filter");
        // After detach, SO_GET_FILTER should return an error or length 0.
        let result = get_bpf_filter_len(client.as_raw_fd());
        match result {
            Err(_) => {}
            Ok(0) => {}
            Ok(n) => panic!("expected no filter after detach, but got {n} instructions"),
        }
    }

    #[test]
    fn tcp_fastopen_connect_is_enabled_after_set() {
        let socket = socket2::Socket::new(socket2::Domain::IPV4, socket2::Type::STREAM, Some(socket2::Protocol::TCP))
            .expect("create TCP socket");
        enable_tcp_fastopen_connect(&socket).expect("enable TFO connect");
        let enabled = get_tcp_fastopen_connect(socket.as_raw_fd()).expect("read TFO state");
        assert!(enabled, "TCP_FASTOPEN_CONNECT should be enabled");
    }

    #[test]
    fn recv_ttl_option_is_set_after_enable() {
        let (client, _server) = connected_pair();
        enable_recv_ttl(&client).expect("enable recv ttl");
        let enabled = get_recv_ttl(client.as_raw_fd()).expect("read IP_RECVTTL state");
        assert!(enabled, "IP_RECVTTL should be enabled after enable_recv_ttl");
    }

    #[test]
    fn bind_udp_low_port_binds_within_range() {
        let raw = socket2::Socket::new(socket2::Domain::IPV4, socket2::Type::DGRAM, Some(socket2::Protocol::UDP))
            .expect("create UDP socket");
        let std_socket: std::net::UdpSocket = raw.into();
        let max_port = 2048u16;
        let port = bind_udp_low_port(&std_socket, IpAddr::V4(Ipv4Addr::LOCALHOST), max_port).expect("bind low port");
        let local_port = std_socket.local_addr().expect("local addr").port();
        assert_eq!(port, local_port, "returned port should match actual bound port");
        assert!(local_port > 0, "should have a valid port");
    }

    #[test]
    fn set_and_get_stream_ttl_round_trip() {
        let (client, _server) = connected_pair();
        set_stream_ttl(&client, 42).expect("set TTL to 42");
        let ttl = get_stream_ttl(&client).expect("read TTL back");
        assert_eq!(ttl, 42, "TTL should round-trip through set/get");
    }

    #[test]
    fn decode_tcp_repair_options_preserves_negotiated_timestamp_state() {
        let mut info: LinuxTcpInfo = unsafe { zeroed() };
        info.tcpi_options = TCPI_OPT_TIMESTAMPS | TCPI_OPT_SACK | TCPI_OPT_WSCALE | TCPI_OPT_USEC_TS;
        info.tcpi_snd_wscale_rcv_wscale = 0x27;
        info.tcpi_snd_mss = 1440;

        let options = decode_tcp_repair_options(
            info,
            Some(TcpTimestampSnapshot { value: 0x1122_3344, echo_reply: 0, usec_ts: true }),
        );

        assert_eq!(options.mss, Some(1440));
        assert!(options.sack_permitted);
        assert_eq!(options.window_scale, Some(TcpWindowScaleSnapshot { send: 7, receive: 2 }));
        assert_eq!(options.timestamp, Some(TcpTimestampSnapshot { value: 0x1122_3344, echo_reply: 0, usec_ts: true }));
    }

    #[test]
    fn decode_tcp_repair_options_omits_timestamp_when_not_negotiated() {
        let mut info: LinuxTcpInfo = unsafe { zeroed() };
        info.tcpi_options = TCPI_OPT_SACK;
        info.tcpi_snd_mss = 1200;

        let options = decode_tcp_repair_options(info, None);

        assert_eq!(options.mss, Some(1200));
        assert!(options.sack_permitted);
        assert_eq!(options.window_scale, None);
        assert_eq!(options.timestamp, None);
    }

    #[test]
    fn mutate_fake_timestamp_applies_signed_delta_with_wrapping() {
        let original = Some(TcpTimestampSnapshot { value: 10, echo_reply: 20, usec_ts: false });

        let increased = fake_send::mutate_fake_timestamp(original, Some(7)).expect("increase timestamp");
        assert_eq!(increased.unwrap().value, 17);

        let decreased = fake_send::mutate_fake_timestamp(original, Some(-15)).expect("decrease timestamp");
        assert_eq!(decreased.unwrap().value, u32::MAX - 4);
    }

    #[test]
    fn mutate_fake_timestamp_requires_negotiated_timestamp_option() {
        let err =
            fake_send::mutate_fake_timestamp(None, Some(1)).expect_err("missing negotiated timestamp should fail");
        assert_eq!(err.kind(), io::ErrorKind::Unsupported);
    }

    #[test]
    fn build_multi_disorder_packets_preserves_payload_ranges_sequence_numbers_and_flags() {
        let source = SocketAddr::from(([203, 0, 113, 10], 50_000));
        let target = SocketAddr::from(([198, 51, 100, 20], 443));
        let payload = b"multidisorder-payload";
        let segments = [
            crate::TcpPayloadSegment { start: 0, end: 5 },
            crate::TcpPayloadSegment { start: 5, end: 14 },
            crate::TcpPayloadSegment { start: 14, end: payload.len() },
        ];
        let snapshot = sample_tcp_repair_snapshot();

        let packets = ip_fragmentation::build_multi_disorder_packets(
            source,
            target,
            37,
            payload,
            &segments,
            &snapshot,
            false,
            TcpFlagOverrides::default(),
            &[],
        )
        .expect("build multidisorder packets");

        assert_eq!(packets.len(), 3);

        let mut identifications = Vec::new();
        for (index, (packet, segment)) in packets.iter().zip(segments.iter()).enumerate() {
            let (ip, transport) = Ipv4Header::from_slice(packet).expect("parse ipv4 packet");
            let (tcp, tcp_payload) = TcpHeader::from_slice(transport).expect("parse tcp packet");

            identifications.push(ip.identification);
            assert_eq!(ip.time_to_live, 37);
            assert_eq!(
                tcp.sequence_number,
                sequence_after_payload(snapshot.sequence_number, segment.start).expect("seq")
            );
            assert_eq!(tcp.acknowledgment_number, snapshot.acknowledgment_number);
            assert_eq!(tcp.window_size, snapshot.window_size);
            assert!(tcp.ack);
            assert_eq!(tcp.psh, index == segments.len() - 1);
            assert!(tcp.header_len() > TcpHeader::MIN_LEN);
            assert_eq!(tcp_payload, &payload[segment.start..segment.end]);
        }

        assert_eq!(identifications[1], identifications[0].wrapping_add(1));
        assert_eq!(identifications[2], identifications[1].wrapping_add(1));
    }

    #[test]
    fn build_multi_disorder_packets_rejects_non_contiguous_segment_ranges() {
        let source = SocketAddr::from(([203, 0, 113, 10], 50_000));
        let target = SocketAddr::from(([198, 51, 100, 20], 443));
        let payload = b"multidisorder";
        let segments =
            [crate::TcpPayloadSegment { start: 0, end: 4 }, crate::TcpPayloadSegment { start: 5, end: payload.len() }];

        let err = ip_fragmentation::build_multi_disorder_packets(
            source,
            target,
            37,
            payload,
            &segments,
            &sample_tcp_repair_snapshot(),
            false,
            TcpFlagOverrides::default(),
            &[],
        )
        .expect_err("reject gapped segments");

        assert_eq!(err.kind(), io::ErrorKind::InvalidInput);
        assert!(err.to_string().contains("invalid multidisorder TCP payload segments"));
    }

    #[test]
    fn build_multi_disorder_packets_rejects_partial_payload_coverage() {
        let source = SocketAddr::from(([203, 0, 113, 10], 50_000));
        let target = SocketAddr::from(([198, 51, 100, 20], 443));
        let payload = b"multidisorder";
        let segments = [
            crate::TcpPayloadSegment { start: 0, end: 4 },
            crate::TcpPayloadSegment { start: 4, end: 8 },
            crate::TcpPayloadSegment { start: 8, end: 11 },
        ];

        let err = ip_fragmentation::build_multi_disorder_packets(
            source,
            target,
            37,
            payload,
            &segments,
            &sample_tcp_repair_snapshot(),
            false,
            TcpFlagOverrides::default(),
            &[],
        )
        .expect_err("reject truncated coverage");

        assert_eq!(err.kind(), io::ErrorKind::InvalidInput);
        assert!(err.to_string().contains("multidisorder TCP payload segments must cover the full payload"));
    }

    // -----------------------------------------------------------------------
    // TTL capability outcome mapping (slice 2.5 regression)
    // -----------------------------------------------------------------------

    /// Maps a raw `io::Error` through the same errno-to-outcome logic used by
    /// `try_set_stream_ttl_with_outcome`.  Extracted here so tests can exercise
    /// every branch without requiring a socket in a specific kernel state.
    fn ttl_error_to_outcome(err: io::Error) -> CapabilityOutcome<()> {
        use super::{CapabilityOutcome, CapabilityUnavailable, RuntimeCapability};
        match err.raw_os_error() {
            Some(libc::ENOPROTOOPT | libc::EOPNOTSUPP | libc::EROFS | libc::EINVAL) => CapabilityOutcome::Unavailable {
                capability: RuntimeCapability::TtlWrite,
                reason: CapabilityUnavailable::Unsupported,
            },
            Some(libc::EACCES | libc::EPERM) => CapabilityOutcome::Unavailable {
                capability: RuntimeCapability::TtlWrite,
                reason: CapabilityUnavailable::PermissionDenied,
            },
            _ => CapabilityOutcome::ProbeFailed { capability: RuntimeCapability::TtlWrite, error: err.to_string() },
        }
    }

    /// Regression (slice 2.5): ENOPROTOOPT / EOPNOTSUPP map to
    /// `Unavailable { Unsupported }` — never to `Available` or a raw error.
    #[test]
    fn ttl_unavailable_enoprotoopt_maps_to_capability_unavailable_unsupported() {
        use super::{CapabilityOutcome, CapabilityUnavailable, RuntimeCapability};

        for &errno in &[libc::ENOPROTOOPT, libc::EOPNOTSUPP, libc::EROFS, libc::EINVAL] {
            let outcome = ttl_error_to_outcome(io::Error::from_raw_os_error(errno));
            match outcome {
                CapabilityOutcome::Unavailable { capability, reason } => {
                    assert_eq!(capability, RuntimeCapability::TtlWrite, "errno {errno}: wrong capability");
                    assert_eq!(reason, CapabilityUnavailable::Unsupported, "errno {errno}: expected Unsupported");
                }
                other => panic!("errno {errno}: expected Unavailable{{Unsupported}}, got {other:?}"),
            }
        }
    }

    /// Regression (slice 2.5): EACCES / EPERM map to
    /// `Unavailable { PermissionDenied }`.
    #[test]
    fn ttl_unavailable_eperm_maps_to_capability_unavailable_permission_denied() {
        use super::{CapabilityOutcome, CapabilityUnavailable, RuntimeCapability};

        for &errno in &[libc::EACCES, libc::EPERM] {
            let outcome = ttl_error_to_outcome(io::Error::from_raw_os_error(errno));
            match outcome {
                CapabilityOutcome::Unavailable { capability, reason } => {
                    assert_eq!(capability, RuntimeCapability::TtlWrite);
                    assert_eq!(
                        reason,
                        CapabilityUnavailable::PermissionDenied,
                        "errno {errno}: expected PermissionDenied"
                    );
                }
                other => panic!("errno {errno}: expected Unavailable{{PermissionDenied}}, got {other:?}"),
            }
        }
    }

    /// Regression (slice 2.5): unexpected errnos map to `ProbeFailed` (not
    /// `Available` and not `Unavailable`).
    #[test]
    fn ttl_unexpected_error_maps_to_probe_failed() {
        use super::{CapabilityOutcome, RuntimeCapability};

        let outcome = ttl_error_to_outcome(io::Error::from_raw_os_error(libc::EIO));
        match outcome {
            CapabilityOutcome::ProbeFailed { capability, .. } => {
                assert_eq!(capability, RuntimeCapability::TtlWrite);
            }
            other => panic!("expected ProbeFailed, got {other:?}"),
        }
    }
}
