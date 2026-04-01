use std::io;
use std::net::{IpAddr, SocketAddr, TcpStream, UdpSocket};
use std::os::fd::AsRawFd;
use std::sync::OnceLock;
use std::time::Duration;

use ripdpi_desync::TcpSegmentHint;
use socket2::{Domain, Protocol, Socket, Type};

#[cfg(any(target_os = "linux", target_os = "android"))]
pub(crate) mod linux;

pub type TcpStageWait = (bool, Duration);

static SEQOVL_SUPPORTED: OnceLock<bool> = OnceLock::new();

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct TcpPayloadSegment {
    pub start: usize,
    pub end: usize,
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
pub fn protect_socket<T: std::os::fd::AsRawFd>(socket: &T, path: &str) -> io::Result<()> {
    linux::protect_socket(socket, path)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn protect_socket<T>(_socket: &T, _path: &str) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
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
    wait: TcpStageWait,
) -> io::Result<()> {
    linux::send_fake_tcp(stream, original_prefix, fake_prefix, ttl, md5sig, default_ttl, wait)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_fake_tcp(
    _stream: &TcpStream,
    _original_prefix: &[u8],
    _fake_prefix: &[u8],
    _ttl: u8,
    _md5sig: bool,
    _default_ttl: u8,
    _wait: TcpStageWait,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn probe_ip_fragmentation_capabilities(protect_path: Option<&str>) -> io::Result<IpFragmentationCapabilities> {
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
) -> io::Result<()> {
    linux::send_ip_fragmented_tcp(stream, payload, split_offset, default_ttl, protect_path, disorder, ipv6_ext)
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
) -> io::Result<()> {
    linux::send_multi_disorder_tcp(stream, payload, segments, default_ttl, protect_path, inter_segment_delay_ms, md5sig)
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
) -> io::Result<()> {
    linux::send_seqovl_tcp(stream, real_chunk, fake_prefix, default_ttl, protect_path, md5sig)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_seqovl_tcp(
    _stream: &TcpStream,
    _real_chunk: &[u8],
    _fake_prefix: &[u8],
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _md5sig: bool,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
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
