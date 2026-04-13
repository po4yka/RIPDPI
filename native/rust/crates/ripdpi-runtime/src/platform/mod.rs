use std::io;
use std::net::{IpAddr, SocketAddr, TcpStream, UdpSocket};
use std::os::fd::{AsRawFd, RawFd};
use std::sync::OnceLock;
use std::time::Duration;

use ripdpi_desync::TcpSegmentHint;
use socket2::{Domain, Protocol, Socket, Type};

#[cfg(any(target_os = "linux", target_os = "android"))]
pub(crate) mod linux;
pub mod protect;
#[cfg(any(target_os = "linux", target_os = "android"))]
pub mod root_helper;
#[cfg(any(target_os = "linux", target_os = "android"))]
pub mod root_helper_client;

pub type TcpStageWait = (bool, Duration);

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct TcpFlagOverrides {
    pub set: u16,
    pub unset: u16,
}

impl TcpFlagOverrides {
    pub const fn is_empty(self) -> bool {
        self.set == 0 && self.unset == 0
    }
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct FakeTcpOptions<'a> {
    pub secondary_fake_prefix: Option<&'a [u8]>,
    pub timestamp_delta_ticks: Option<i32>,
    pub protect_path: Option<&'a str>,
    pub fake_flags: TcpFlagOverrides,
    pub orig_flags: TcpFlagOverrides,
}

static SEQOVL_SUPPORTED: OnceLock<bool> = OnceLock::new();

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TcpPayloadSegment {
    pub start: usize,
    pub end: usize,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct TcpActivationState {
    pub has_timestamp: Option<bool>,
    pub window_size: Option<i64>,
    pub mss: Option<i64>,
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct IpFragmentationCapabilities {
    pub raw_ipv4: bool,
    pub raw_ipv6: bool,
    pub tcp_repair: bool,
}

impl IpFragmentationCapabilities {
    pub const fn supports_udp_ip_fragmentation(self, ipv6_enabled: bool) -> bool {
        self.raw_ipv4 && (!ipv6_enabled || self.raw_ipv6)
    }

    pub const fn supports_tcp_ip_fragmentation(self, ipv6_enabled: bool) -> bool {
        self.supports_udp_ip_fragmentation(ipv6_enabled) && self.tcp_repair
    }
}

#[doc(hidden)]
pub fn read_unaligned_raw_fd(bytes: &[u8]) -> Option<RawFd> {
    if bytes.len() < std::mem::size_of::<RawFd>() {
        return None;
    }
    // SAFETY: `bytes` is a valid slice; we read exactly `size_of::<RawFd>()`
    // bytes from its base pointer and `read_unaligned` permits any alignment.
    Some(unsafe { std::ptr::read_unaligned(bytes.as_ptr().cast::<RawFd>()) })
}

#[doc(hidden)]
pub fn extract_scm_rights_fd(msg: &libc::msghdr) -> Option<RawFd> {
    // SAFETY: `msg` must describe a control buffer populated by `recvmsg`; the
    // caller owns the underlying storage for the duration of this traversal.
    let mut cmsg = unsafe { libc::CMSG_FIRSTHDR(msg) };
    while !cmsg.is_null() {
        // SAFETY: `cmsg` is either null or a valid control header pointer from
        // the `CMSG_*` traversal macros.
        let hdr = unsafe { &*cmsg };
        if hdr.cmsg_level == libc::SOL_SOCKET && hdr.cmsg_type == libc::SCM_RIGHTS {
            // SAFETY: `SCM_RIGHTS` stores at least one `RawFd` in the
            // associated control message payload.
            let data_ptr = unsafe { libc::CMSG_DATA(cmsg) };
            // SAFETY: `data_ptr` points into the live ancillary buffer owned by
            // `msg`; SCM_RIGHTS stores at least one file descriptor payload.
            let data = unsafe { std::slice::from_raw_parts(data_ptr.cast::<u8>(), std::mem::size_of::<RawFd>()) };
            return read_unaligned_raw_fd(data);
        }
        // SAFETY: advances within the same ancillary buffer described by `msg`.
        cmsg = unsafe { libc::CMSG_NXTHDR(msg, cmsg) };
    }
    None
}

#[doc(hidden)]
pub fn recv_line_with_optional_fd(
    fd: RawFd,
    buf: &mut [u8],
    cmsg_buf: &mut [u8],
    eof_message: &'static str,
) -> io::Result<(Vec<u8>, Option<RawFd>)> {
    let mut iov = libc::iovec { iov_base: buf.as_mut_ptr().cast(), iov_len: buf.len() };

    // SAFETY: zeroed bytes are a valid initial state for `msghdr` before the
    // pointer fields are explicitly populated below.
    let mut msg: libc::msghdr = unsafe { std::mem::zeroed() };
    msg.msg_iov = &mut iov;
    msg.msg_iovlen = 1;
    msg.msg_control = cmsg_buf.as_mut_ptr().cast();
    msg.msg_controllen = cmsg_buf.len() as _;

    // SAFETY: `msg` points at live caller-owned iov and control buffers for the
    // duration of this syscall.
    let n = unsafe { libc::recvmsg(fd, &mut msg, 0) };
    if n < 0 {
        return Err(io::Error::last_os_error());
    }
    if n == 0 {
        return Err(io::Error::new(io::ErrorKind::UnexpectedEof, eof_message));
    }

    let data = &buf[..n as usize];
    let data = data.strip_suffix(b"\n").unwrap_or(data);
    Ok((data.to_vec(), extract_scm_rights_fd(&msg)))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub const fn supports_fake_retransmit() -> bool {
    true
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub const fn supports_fake_retransmit() -> bool {
    false
}

pub fn detect_default_ttl() -> io::Result<u8> {
    let socket = Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP))?;
    let ttl = socket.ttl_v4()?;
    u8::try_from(ttl).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "socket ttl exceeds u8"))
}

pub fn seqovl_supported() -> bool {
    *SEQOVL_SUPPORTED
        .get_or_init(|| probe_ip_fragmentation_capabilities(None).map(|caps| caps.tcp_repair).unwrap_or(false))
}

/// Return io_uring capabilities detected at startup.
#[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
pub fn io_uring_capabilities() -> ripdpi_io_uring::IoUringCapabilities {
    ripdpi_io_uring::io_uring_capabilities()
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn enable_tcp_fastopen_connect<T: std::os::fd::AsRawFd>(socket: &T) -> io::Result<()> {
    linux::enable_tcp_fastopen_connect(socket)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn enable_tcp_fastopen_connect<T>(_socket: &T) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn set_tcp_md5sig(stream: &TcpStream, key_len: u16) -> io::Result<()> {
    linux::set_tcp_md5sig(stream, key_len)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn set_tcp_md5sig(_stream: &TcpStream, _key_len: u16) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn protect_socket<T: std::os::fd::AsRawFd>(socket: &T, path: Option<&str>) -> io::Result<()> {
    // Prefer JNI callback (no Unix socket server needed).
    if protect::has_protect_callback() {
        return protect::protect_socket_via_callback(socket.as_raw_fd());
    }
    // Fallback: Unix domain socket + SCM_RIGHTS.
    if let Some(p) = path {
        linux::protect_socket(socket, p)
    } else {
        Ok(())
    }
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn protect_socket<T: std::os::fd::AsRawFd>(socket: &T, _path: Option<&str>) -> io::Result<()> {
    // Prefer JNI callback on any platform.
    if protect::has_protect_callback() {
        return protect::protect_socket_via_callback(socket.as_raw_fd());
    }
    Ok(())
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn original_dst(stream: &TcpStream) -> io::Result<SocketAddr> {
    linux::original_dst(stream)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn original_dst(_stream: &TcpStream) -> io::Result<SocketAddr> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn attach_drop_sack(stream: &TcpStream) -> io::Result<()> {
    linux::attach_drop_sack(stream)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn attach_drop_sack(_stream: &TcpStream) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn detach_drop_sack(stream: &TcpStream) -> io::Result<()> {
    linux::detach_drop_sack(stream)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn detach_drop_sack(_stream: &TcpStream) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn set_tcp_window_clamp(stream: &TcpStream, size: u32) -> io::Result<()> {
    linux::set_tcp_window_clamp(stream, size)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn set_tcp_window_clamp(_stream: &TcpStream, _size: u32) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn set_rcvbuf(fd: &impl AsRawFd, size: u32) -> io::Result<()> {
    linux::set_rcvbuf(fd, size)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn set_rcvbuf(_fd: &impl AsRawFd, _size: u32) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_fake_rst(
    stream: &TcpStream,
    default_ttl: u8,
    protect_path: Option<&str>,
    flags: TcpFlagOverrides,
) -> io::Result<()> {
    if let Some(result) = root_helper::with_root_helper(|h| h.send_fake_rst(stream.as_raw_fd(), default_ttl, flags)) {
        return result;
    }
    linux::send_fake_rst(stream, default_ttl, protect_path, flags)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_fake_rst(
    _stream: &TcpStream,
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _flags: TcpFlagOverrides,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn attach_strip_timestamps(stream: &TcpStream) -> io::Result<()> {
    linux::attach_strip_timestamps(stream)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn attach_strip_timestamps(_stream: &TcpStream) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn bind_udp_low_port(socket: &UdpSocket, local_ip: IpAddr, max_port: u16) -> io::Result<u16> {
    linux::bind_udp_low_port(socket, local_ip, max_port)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn bind_udp_low_port(_socket: &UdpSocket, _local_ip: IpAddr, _max_port: u16) -> io::Result<u16> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_fake_tcp(
    stream: &TcpStream,
    original_prefix: &[u8],
    fake_prefix: &[u8],
    ttl: u8,
    md5sig: bool,
    default_ttl: u8,
    options: FakeTcpOptions<'_>,
    wait: TcpStageWait,
) -> io::Result<()> {
    linux::send_fake_tcp(stream, original_prefix, fake_prefix, ttl, md5sig, default_ttl, options, wait)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_fake_tcp(
    _stream: &TcpStream,
    _original_prefix: &[u8],
    _fake_prefix: &[u8],
    _ttl: u8,
    _md5sig: bool,
    _default_ttl: u8,
    _options: FakeTcpOptions<'_>,
    _wait: TcpStageWait,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_flagged_tcp_payload(
    stream: &TcpStream,
    payload: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: TcpFlagOverrides,
) -> io::Result<()> {
    if let Some(result) = root_helper::with_root_helper(|h| {
        let res = h.send_flagged_tcp_payload(stream.as_raw_fd(), payload, default_ttl, md5sig, flags)?;
        if let Some(replacement_fd) = res {
            swap_replacement_fd(stream.as_raw_fd(), replacement_fd)?;
        }
        Ok(())
    }) {
        return result;
    }
    linux::send_flagged_tcp_payload(stream, payload, default_ttl, protect_path, md5sig, flags)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_flagged_tcp_payload(
    _stream: &TcpStream,
    _payload: &[u8],
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _md5sig: bool,
    _flags: TcpFlagOverrides,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn probe_ip_fragmentation_capabilities(protect_path: Option<&str>) -> io::Result<IpFragmentationCapabilities> {
    if let Some(result) = root_helper::with_root_helper(root_helper_client::RootHelperClient::probe_capabilities) {
        return result;
    }
    linux::probe_ip_fragmentation_capabilities(protect_path)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn probe_ip_fragmentation_capabilities(_protect_path: Option<&str>) -> io::Result<IpFragmentationCapabilities> {
    Ok(IpFragmentationCapabilities::default())
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_ip_fragmented_udp(
    upstream: &UdpSocket,
    target: SocketAddr,
    payload: &[u8],
    split_offset: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    disorder: bool,
    ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
) -> io::Result<()> {
    if let Some(result) = root_helper::with_root_helper(|h| {
        h.send_ip_fragmented_udp(upstream.as_raw_fd(), target, payload, split_offset, default_ttl, disorder)
    }) {
        return result;
    }
    linux::send_ip_fragmented_udp(
        upstream,
        target,
        payload,
        split_offset,
        default_ttl,
        protect_path,
        disorder,
        ipv6_ext,
    )
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_ip_fragmented_udp(
    _upstream: &UdpSocket,
    _target: SocketAddr,
    _payload: &[u8],
    _split_offset: usize,
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _disorder: bool,
    _ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_ip_fragmented_tcp(
    stream: &TcpStream,
    payload: &[u8],
    split_offset: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    disorder: bool,
    ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    flags: TcpFlagOverrides,
) -> io::Result<()> {
    if let Some(result) = root_helper::with_root_helper(|h| {
        let res = h.send_ip_fragmented_tcp(stream.as_raw_fd(), payload, split_offset, default_ttl, disorder, flags)?;
        if let Some(replacement_fd) = res {
            swap_replacement_fd(stream.as_raw_fd(), replacement_fd)?;
        }
        Ok(())
    }) {
        return result;
    }
    linux::send_ip_fragmented_tcp(stream, payload, split_offset, default_ttl, protect_path, disorder, ipv6_ext, flags)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_ip_fragmented_tcp(
    _stream: &TcpStream,
    _payload: &[u8],
    _split_offset: usize,
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _disorder: bool,
    _ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    _flags: TcpFlagOverrides,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_multi_disorder_tcp(
    stream: &TcpStream,
    payload: &[u8],
    segments: &[TcpPayloadSegment],
    default_ttl: u8,
    protect_path: Option<&str>,
    inter_segment_delay_ms: u32,
    md5sig: bool,
    flags: TcpFlagOverrides,
) -> io::Result<()> {
    if let Some(result) = root_helper::with_root_helper(|h| {
        let res = h.send_multi_disorder_tcp(
            stream.as_raw_fd(),
            payload,
            segments,
            default_ttl,
            inter_segment_delay_ms,
            md5sig,
            flags,
        )?;
        if let Some(replacement_fd) = res {
            swap_replacement_fd(stream.as_raw_fd(), replacement_fd)?;
        }
        Ok(())
    }) {
        return result;
    }
    linux::send_multi_disorder_tcp(
        stream,
        payload,
        segments,
        default_ttl,
        protect_path,
        inter_segment_delay_ms,
        md5sig,
        flags,
    )
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_multi_disorder_tcp(
    _stream: &TcpStream,
    _payload: &[u8],
    _segments: &[TcpPayloadSegment],
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _inter_segment_delay_ms: u32,
    _md5sig: bool,
    _flags: TcpFlagOverrides,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_seqovl_tcp(
    stream: &TcpStream,
    real_chunk: &[u8],
    fake_prefix: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: TcpFlagOverrides,
) -> io::Result<()> {
    if let Some(result) = root_helper::with_root_helper(|h| {
        let res = h.send_seqovl_tcp(stream.as_raw_fd(), real_chunk, fake_prefix, default_ttl, md5sig, flags)?;
        if let Some(replacement_fd) = res {
            swap_replacement_fd(stream.as_raw_fd(), replacement_fd)?;
        }
        Ok(())
    }) {
        return result;
    }
    linux::send_seqovl_tcp(stream, real_chunk, fake_prefix, default_ttl, protect_path, md5sig, flags)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_seqovl_tcp(
    _stream: &TcpStream,
    _real_chunk: &[u8],
    _fake_prefix: &[u8],
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _md5sig: bool,
    _flags: TcpFlagOverrides,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

/// Atomically replace the target fd with a replacement fd received from the
/// root helper. Uses `dup2` to swap and then closes the replacement.
#[cfg(any(target_os = "linux", target_os = "android"))]
fn swap_replacement_fd(target_fd: std::os::fd::RawFd, replacement_fd: std::os::fd::RawFd) -> io::Result<()> {
    linux::dup2_fd(replacement_fd, target_fd)?;
    linux::close_fd(replacement_fd)?;
    Ok(())
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn wait_tcp_stage(stream: &TcpStream, wait_send: bool, await_interval: Duration) -> io::Result<()> {
    linux::wait_tcp_stage(stream, wait_send, await_interval)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn wait_tcp_stage(_stream: &TcpStream, _wait_send: bool, _await_interval: Duration) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn tcp_segment_hint(stream: &TcpStream) -> io::Result<Option<TcpSegmentHint>> {
    linux::tcp_segment_hint(stream)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn tcp_segment_hint(_stream: &TcpStream) -> io::Result<Option<TcpSegmentHint>> {
    Ok(None)
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn tcp_activation_state(stream: &TcpStream) -> io::Result<Option<TcpActivationState>> {
    linux::tcp_activation_state(stream)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn tcp_activation_state(_stream: &TcpStream) -> io::Result<Option<TcpActivationState>> {
    Ok(None)
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn tcp_round_trip_time_ms(stream: &TcpStream) -> io::Result<Option<u64>> {
    linux::tcp_round_trip_time_ms(stream)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn tcp_round_trip_time_ms(_stream: &TcpStream) -> io::Result<Option<u64>> {
    Ok(None)
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn tcp_total_retransmissions<T: AsRawFd>(socket: &T) -> io::Result<Option<u32>> {
    linux::tcp_total_retransmissions(socket)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn tcp_total_retransmissions<T: AsRawFd>(_socket: &T) -> io::Result<Option<u32>> {
    Ok(None)
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn enable_recv_ttl(stream: &TcpStream) -> io::Result<()> {
    linux::enable_recv_ttl(stream)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn enable_recv_ttl(_stream: &TcpStream) -> io::Result<()> {
    Ok(()) // best-effort; no-op on non-Linux
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn read_chunk_with_ttl(stream: &TcpStream, buf: &mut [u8]) -> io::Result<(usize, Option<u8>)> {
    linux::read_chunk_with_ttl(stream, buf)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn read_chunk_with_ttl(stream: &TcpStream, buf: &mut [u8]) -> io::Result<(usize, Option<u8>)> {
    use std::io::Read;
    Ok(((&*stream).read(buf)?, None))
}

#[cfg(test)]
mod tests {
    use std::mem::{size_of, zeroed};
    use std::ptr;

    use super::{extract_scm_rights_fd, read_unaligned_raw_fd};

    #[test]
    fn read_unaligned_raw_fd_reads_i32_payload() {
        let fd = 0x0102_0304_i32;
        let bytes = fd.to_ne_bytes();

        assert_eq!(read_unaligned_raw_fd(&bytes), Some(fd));
    }

    #[test]
    fn read_unaligned_raw_fd_rejects_short_payload() {
        assert_eq!(read_unaligned_raw_fd(&[1, 2, 3]), None);
    }

    #[test]
    fn extract_scm_rights_fd_returns_received_fd() {
        let control_len = unsafe { libc::CMSG_SPACE(size_of::<libc::c_int>() as _) } as usize;
        let mut control = vec![0u8; control_len];
        let mut msg: libc::msghdr = unsafe { zeroed() };
        msg.msg_control = control.as_mut_ptr().cast();
        msg.msg_controllen = control_len as _;

        // SAFETY: `msg` describes the writable `control` buffer above.
        let cmsg = unsafe { libc::CMSG_FIRSTHDR(&msg) };
        assert!(!cmsg.is_null());

        let expected_fd: libc::c_int = 123;
        unsafe {
            (*cmsg).cmsg_level = libc::SOL_SOCKET;
            (*cmsg).cmsg_type = libc::SCM_RIGHTS;
            (*cmsg).cmsg_len = libc::CMSG_LEN(size_of::<libc::c_int>() as _) as _;
            ptr::write_unaligned(libc::CMSG_DATA(cmsg).cast(), expected_fd);
        }

        assert_eq!(extract_scm_rights_fd(&msg), Some(expected_fd));
    }

    #[test]
    fn extract_scm_rights_fd_ignores_non_fd_control_messages() {
        let control_len = unsafe { libc::CMSG_SPACE(size_of::<libc::c_int>() as _) } as usize;
        let mut control = vec![0u8; control_len];
        let mut msg: libc::msghdr = unsafe { zeroed() };
        msg.msg_control = control.as_mut_ptr().cast();
        msg.msg_controllen = control_len as _;

        // SAFETY: `msg` describes the writable `control` buffer above.
        let cmsg = unsafe { libc::CMSG_FIRSTHDR(&msg) };
        assert!(!cmsg.is_null());

        unsafe {
            (*cmsg).cmsg_level = libc::IPPROTO_IP;
            (*cmsg).cmsg_type = libc::IP_TTL;
            (*cmsg).cmsg_len = libc::CMSG_LEN(size_of::<libc::c_int>() as _) as _;
            ptr::write_unaligned(libc::CMSG_DATA(cmsg).cast(), 64 as libc::c_int);
        }

        assert_eq!(extract_scm_rights_fd(&msg), None);
    }
}
