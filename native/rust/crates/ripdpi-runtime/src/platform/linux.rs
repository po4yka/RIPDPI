//! Linux/Android platform socket operations.
//!
//! This module intentionally uses raw `libc::setsockopt`/`getsockopt` for
//! kernel-specific options not available in `socket2` (as of 0.5):
//! TCP_INFO, TCP_MD5SIG, TCP_FASTOPEN_CONNECT, SO_ATTACH_FILTER,
//! SO_ORIGINAL_DST, IP_RECVTTL, and `recvmsg` with CMSG ancillary data.
//!
//! Standard socket options use `socket2::SockRef` (see [`set_stream_ttl`]).
//! Last audited: 2026-03-24 against socket2 0.5.10.

use std::io::{self, Read};
use std::mem::{size_of, zeroed};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpStream};
use std::os::fd::AsRawFd;
use std::os::unix::net::UnixStream;
use std::ptr;
use std::thread;
use std::time::{Duration, Instant};

use ripdpi_desync::TcpSegmentHint;
use socket2::SockRef;

use super::TcpStageWait;

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

#[repr(C)]
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
    // SAFETY: live TCP socket; `1i32` is valid for TCP_FASTOPEN_CONNECT.
    unsafe { setsockopt_raw(socket.as_raw_fd(), libc::IPPROTO_TCP, libc::TCP_FASTOPEN_CONNECT, &1i32) }
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

    let stream = UnixStream::connect(path)?;
    stream.set_read_timeout(Some(Duration::from_secs(1)))?;
    stream.set_write_timeout(Some(Duration::from_secs(1)))?;

    let payload = [b'1'];
    let iov = [IoSlice::new(&payload)];
    let fd = socket.as_raw_fd();
    let fds = [fd];
    let cmsg = [ControlMessage::ScmRights(&fds)];
    sendmsg::<()>(stream.as_raw_fd(), &iov, &cmsg, MsgFlags::empty(), None)
        .map_err(|e| io::Error::from_raw_os_error(e as i32))?;

    let mut ack = [0u8; 1];
    (&stream).read_exact(&mut ack)?;
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
    // SAFETY: `0i32` is a valid payload for SO_DETACH_FILTER and `stream` is a
    // live TCP socket.
    unsafe { setsockopt_raw(stream.as_raw_fd(), libc::SOL_SOCKET, libc::SO_DETACH_FILTER, &0i32) }
}

pub fn enable_recv_ttl(stream: &TcpStream) -> io::Result<()> {
    let fd = stream.as_raw_fd();
    // On dual-stack sockets both options may be valid, so attempt both and
    // succeed if at least one takes effect (mirrors `set_stream_ttl` pattern).
    // SAFETY: `1i32` enables IP_RECVTTL / IPV6_RECVHOPLIMIT and `stream` is a
    // live TCP socket.
    let ipv4 = unsafe { setsockopt_raw(fd, libc::IPPROTO_IP, libc::IP_RECVTTL, &1i32) };
    let ipv6 = unsafe { setsockopt_raw(fd, libc::IPPROTO_IPV6, libc::IPV6_RECVHOPLIMIT, &1i32) };
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

pub fn send_fake_tcp(
    stream: &TcpStream,
    original_prefix: &[u8],
    fake_prefix: &[u8],
    ttl: u8,
    md5sig: bool,
    default_ttl: u8,
    wait: TcpStageWait,
) -> io::Result<()> {
    if original_prefix.is_empty() {
        return Ok(());
    }

    let fd = stream.as_raw_fd();
    let region_len = original_prefix.len().max(fake_prefix.len());
    let region = alloc_region(region_len)?;

    // When default_ttl is 0 (auto-detect), read the current TTL before
    // modifying so we can always restore the original value afterward.
    let restore_ttl = if default_ttl != 0 { default_ttl } else { get_stream_ttl(stream).unwrap_or(64) };

    let result = (|| {
        write_region(region, fake_prefix, region_len);

        let (pipe_r, pipe_w) = nix::unistd::pipe().map_err(|e| io::Error::from_raw_os_error(e as i32))?;
        // pipe_r and pipe_w are OwnedFd -- closed automatically on drop.

        set_stream_ttl(stream, ttl)?;
        if md5sig {
            set_tcp_md5sig(stream, 5)?;
        }

        let iov = libc::iovec { iov_base: region.cast(), iov_len: original_prefix.len() };
        // SAFETY: `iov` references an anonymous writable mapping whose lifetime
        // extends until after the splice completes. vmsplice is not in nix 0.29.
        let queued = unsafe { libc::vmsplice(pipe_w.as_raw_fd(), &iov, 1, libc::SPLICE_F_GIFT as libc::c_uint) };
        if queued < 0 {
            return Err(io::Error::last_os_error());
        }
        if queued as usize != original_prefix.len() {
            return Err(io::Error::new(io::ErrorKind::WriteZero, "partial vmsplice during fake tcp send"));
        }

        let mut moved = 0usize;
        while moved < original_prefix.len() {
            let chunk = nix::fcntl::splice(
                &pipe_r,
                None,
                stream,
                None,
                original_prefix.len() - moved,
                nix::fcntl::SpliceFFlags::empty(),
            )
            .map_err(|e| io::Error::from_raw_os_error(e as i32))?;
            if chunk == 0 {
                return Err(io::Error::new(io::ErrorKind::WriteZero, "partial splice during fake tcp send"));
            }
            moved += chunk;
        }

        wait_tcp_stage_fd(fd, wait.0, wait.1)?;
        if md5sig {
            set_tcp_md5sig(stream, 0)?;
        }
        set_stream_ttl(stream, restore_ttl)?;
        // INTENTIONAL DATA RACE: The vmsplice with SPLICE_F_GIFT transferred
        // page ownership to the kernel. Overwriting the region here relies on
        // the kernel retransmitting from the updated page content (which now
        // holds the real `original_prefix` bytes). This is the core mechanism
        // that makes the fake-retransmit desync strategy work on Linux.
        write_region(region, original_prefix, region_len);
        Ok(())
    })();

    if md5sig {
        let _ = set_tcp_md5sig(stream, 0);
    }
    let _ = set_stream_ttl(stream, restore_ttl);
    free_region(region, region_len);
    result
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
    if let Ok(ttl) = socket.ttl() {
        return u8::try_from(ttl)
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "socket ttl exceeds u8"));
    }
    let hops = socket.unicast_hops_v6()?;
    u8::try_from(hops).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "socket hop limit exceeds u8"))
}

fn set_stream_ttl(stream: &TcpStream, ttl: u8) -> io::Result<()> {
    let socket = SockRef::from(stream);
    let ipv4 = socket.set_ttl(ttl as u32);
    let ipv6 = socket.set_unicast_hops_v6(ttl as u32);
    match (ipv4, ipv6) {
        (Ok(()), _) | (_, Ok(())) => Ok(()),
        (Err(err), _) => Err(err),
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

pub fn tcp_round_trip_time_ms(stream: &TcpStream) -> io::Result<Option<u64>> {
    let Some(info) = read_tcp_info(stream.as_raw_fd())? else {
        return Ok(None);
    };
    if info.tcpi_state != TCP_ESTABLISHED {
        return Ok(None);
    }
    Ok(Some(u64::from(info.tcpi_rtt / 1_000)))
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

fn alloc_region(len: usize) -> io::Result<*mut u8> {
    use nix::sys::mman::{mmap_anonymous, MapFlags, ProtFlags};
    use std::num::NonZeroUsize;
    let size =
        NonZeroUsize::new(len).ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "zero-length mmap region"))?;
    // SAFETY: anonymous private mapping; no backing fd; no aliasing with existing mappings.
    let ptr =
        unsafe { mmap_anonymous(None, size, ProtFlags::PROT_READ | ProtFlags::PROT_WRITE, MapFlags::MAP_PRIVATE) }
            .map_err(|e| io::Error::from_raw_os_error(e as i32))?;
    Ok(ptr.as_ptr().cast())
}

fn free_region(region: *mut u8, len: usize) {
    use std::ptr::NonNull;
    if let Some(ptr) = NonNull::new(region) {
        if len != 0 {
            // SAFETY: `region` was allocated by mmap with the same length.
            let _ = unsafe { nix::sys::mman::munmap(ptr.cast(), len) };
        }
    }
}

fn write_region(region: *mut u8, data: &[u8], len: usize) {
    // SAFETY: `region` points to a writable mapping of `len` bytes.
    unsafe {
        ptr::write_bytes(region, 0, len);
        ptr::copy_nonoverlapping(data.as_ptr(), region, data.len().min(len));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::mem::zeroed;
    use std::net::TcpListener;
    use std::slice;

    fn connected_pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
        let addr = listener.local_addr().expect("listener addr");
        let client = TcpStream::connect(addr).expect("connect client");
        let (server, _) = listener.accept().expect("accept client");
        (client, server)
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
}
