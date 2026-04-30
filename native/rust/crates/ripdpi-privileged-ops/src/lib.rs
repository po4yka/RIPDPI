use std::io;
use std::net::{IpAddr, SocketAddr, TcpStream, UdpSocket};
use std::os::fd::AsRawFd;
use std::time::Duration;

pub mod experimental_tier3;
#[cfg(any(target_os = "linux", target_os = "android"))]
mod linux;

pub use experimental_tier3::{
    recv_icmp_wrapped_udp, send_icmp_wrapped_udp, send_syn_hide_tcp, IcmpWrappedUdpRecvFilter, IcmpWrappedUdpRole,
    IcmpWrappedUdpSpec, ReceivedIcmpWrappedUdp, SynHideMarkerKind, SynHideTcpSpec,
};
use ripdpi_desync::TcpSegmentHint;

pub type TcpStageWait = (bool, Duration);

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum RuntimeCapability {
    TtlWrite,
    RawTcpFakeSend,
    RawUdpFragmentation,
    ReplacementSocket,
    RootHelperAvailable,
    VpnProtectCallback,
    NetworkBinding,
}

impl RuntimeCapability {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::TtlWrite => "ttl_write",
            Self::RawTcpFakeSend => "raw_tcp_fake_send",
            Self::RawUdpFragmentation => "raw_udp_fragmentation",
            Self::ReplacementSocket => "replacement_socket",
            Self::RootHelperAvailable => "root_helper_available",
            Self::VpnProtectCallback => "vpn_protect_callback",
            Self::NetworkBinding => "network_binding",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CapabilityUnavailable {
    NotProbed,
    Unsupported,
    PermissionDenied,
    MissingRootHelper,
}

#[derive(Debug, Clone)]
pub enum CapabilityOutcome<T> {
    Available(T),
    Unavailable { capability: RuntimeCapability, reason: CapabilityUnavailable },
    ProbeFailed { capability: RuntimeCapability, error: String },
}

impl<T> CapabilityOutcome<T> {
    pub fn is_available(&self) -> bool {
        matches!(self, Self::Available(_))
    }

    pub fn capability(&self) -> Option<RuntimeCapability> {
        match self {
            Self::Available(_) => None,
            Self::Unavailable { capability, .. } | Self::ProbeFailed { capability, .. } => Some(*capability),
        }
    }

    pub fn take(self) -> Option<T> {
        match self {
            Self::Available(value) => Some(value),
            Self::Unavailable { .. } | Self::ProbeFailed { .. } => None,
        }
    }
}

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

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct OrderedTcpSegment<'a> {
    pub payload: &'a [u8],
    pub ttl: u8,
    pub flags: TcpFlagOverrides,
    pub sequence_offset: usize,
    pub use_fake_timestamp: bool,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct FakeTcpOptions<'a> {
    pub secondary_fake_prefix: Option<&'a [u8]>,
    pub timestamp_delta_ticks: Option<i32>,
    pub protect_path: Option<&'a str>,
    pub fake_flags: TcpFlagOverrides,
    pub orig_flags: TcpFlagOverrides,
    pub require_raw_path: bool,
    pub force_raw_original: bool,
    pub ipv4_identifications: Vec<u16>,
}

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

pub fn detect_default_ttl() -> io::Result<u8> {
    let socket = socket2::Socket::new(socket2::Domain::IPV4, socket2::Type::STREAM, Some(socket2::Protocol::TCP))?;
    let ttl = socket.ttl_v4()?;
    u8::try_from(ttl).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "socket ttl exceeds u8"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn protect_socket<T: AsRawFd>(socket: &T, path: Option<&str>) -> io::Result<()> {
    if let Some(path) = path {
        linux::protect_socket(socket, path)
    } else {
        Ok(())
    }
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn protect_socket<T: AsRawFd>(_socket: &T, _path: Option<&str>) -> io::Result<()> {
    Ok(())
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn swap_replacement_fd(target_fd: libc::c_int, replacement_fd: libc::c_int) -> io::Result<()> {
    linux::dup2_fd(replacement_fd, target_fd)?;
    linux::close_fd(replacement_fd)?;
    Ok(())
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn swap_replacement_fd(_target_fd: libc::c_int, _replacement_fd: libc::c_int) -> io::Result<()> {
    unsupported()
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn enable_tcp_fastopen_connect<T: AsRawFd>(socket: &T) -> io::Result<()> {
    linux::enable_tcp_fastopen_connect(socket)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn enable_tcp_fastopen_connect<T>(_socket: &T) -> io::Result<()> {
    unsupported()
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn set_tcp_md5sig(stream: &TcpStream, key_len: u16) -> io::Result<()> {
    linux::set_tcp_md5sig(stream, key_len)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn set_tcp_md5sig(_stream: &TcpStream, _key_len: u16) -> io::Result<()> {
    unsupported()
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
    unsupported()
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn detach_drop_sack(stream: &TcpStream) -> io::Result<()> {
    linux::detach_drop_sack(stream)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn detach_drop_sack(_stream: &TcpStream) -> io::Result<()> {
    unsupported()
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn set_tcp_window_clamp(stream: &TcpStream, size: u32) -> io::Result<()> {
    linux::set_tcp_window_clamp(stream, size)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn set_tcp_window_clamp(_stream: &TcpStream, _size: u32) -> io::Result<()> {
    unsupported()
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn set_rcvbuf(fd: &impl AsRawFd, size: u32) -> io::Result<()> {
    linux::set_rcvbuf(fd, size)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn set_rcvbuf(_fd: &impl AsRawFd, _size: u32) -> io::Result<()> {
    unsupported()
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn attach_strip_timestamps(stream: &TcpStream) -> io::Result<()> {
    linux::attach_strip_timestamps(stream)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn attach_strip_timestamps(_stream: &TcpStream) -> io::Result<()> {
    unsupported()
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
pub fn wait_tcp_stage(stream: &TcpStream, wait_send: bool, await_interval: Duration) -> io::Result<()> {
    linux::wait_tcp_stage(stream, wait_send, await_interval)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn wait_tcp_stage(_stream: &TcpStream, _wait_send: bool, _await_interval: Duration) -> io::Result<()> {
    unsupported()
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
    Ok(())
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

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn try_set_stream_ttl_with_outcome(stream: &TcpStream, ttl: u8) -> CapabilityOutcome<()> {
    linux::try_set_stream_ttl_with_outcome(stream, ttl)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn try_set_stream_ttl_with_outcome(_stream: &TcpStream, _ttl: u8) -> CapabilityOutcome<()> {
    CapabilityOutcome::Unavailable {
        capability: RuntimeCapability::TtlWrite,
        reason: CapabilityUnavailable::Unsupported,
    }
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn probe_ip_fragmentation_capabilities(protect_path: Option<&str>) -> io::Result<IpFragmentationCapabilities> {
    linux::probe_ip_fragmentation_capabilities(protect_path)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn probe_ip_fragmentation_capabilities(_protect_path: Option<&str>) -> io::Result<IpFragmentationCapabilities> {
    Ok(IpFragmentationCapabilities::default())
}

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_fake_rst(
    stream: &TcpStream,
    default_ttl: u8,
    protect_path: Option<&str>,
    flags: TcpFlagOverrides,
    ipv4_identification: Option<u16>,
) -> io::Result<()> {
    linux::send_fake_rst(stream, default_ttl, protect_path, flags, ipv4_identification)
}

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_flagged_tcp_payload(
    stream: &TcpStream,
    payload: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: TcpFlagOverrides,
    ipv4_identification: Option<u16>,
) -> io::Result<()> {
    linux::send_flagged_tcp_payload(stream, payload, default_ttl, protect_path, md5sig, flags, ipv4_identification)
}

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_seqovl_tcp(
    stream: &TcpStream,
    real_chunk: &[u8],
    fake_prefix: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: TcpFlagOverrides,
    ipv4_identification: Option<u16>,
) -> io::Result<()> {
    linux::send_seqovl_tcp(
        stream,
        real_chunk,
        fake_prefix,
        default_ttl,
        protect_path,
        md5sig,
        flags,
        ipv4_identification,
    )
}

#[allow(clippy::too_many_arguments)]
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
    ipv4_identifications: &[u16],
) -> io::Result<()> {
    linux::send_multi_disorder_tcp(
        stream,
        payload,
        segments,
        default_ttl,
        protect_path,
        inter_segment_delay_ms,
        md5sig,
        flags,
        ipv4_identifications,
    )
}

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_ordered_tcp_segments(
    stream: &TcpStream,
    segments: &[OrderedTcpSegment<'_>],
    original_payload_len: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    timestamp_delta_ticks: Option<i32>,
    ipv4_identifications: &[u16],
    wait: TcpStageWait,
) -> io::Result<()> {
    linux::send_ordered_tcp_segments(
        stream,
        segments,
        original_payload_len,
        default_ttl,
        protect_path,
        md5sig,
        timestamp_delta_ticks,
        ipv4_identifications,
        wait,
    )
}

#[allow(clippy::too_many_arguments)]
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
    ipv4_identification: Option<u16>,
) -> io::Result<()> {
    linux::send_ip_fragmented_tcp(
        stream,
        payload,
        split_offset,
        default_ttl,
        protect_path,
        disorder,
        ipv6_ext,
        flags,
        ipv4_identification,
    )
}

#[allow(clippy::too_many_arguments)]
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
    ipv4_identification: Option<u16>,
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
        ipv4_identification,
    )
}

#[allow(clippy::too_many_arguments)]
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_fake_rst(
    _stream: &TcpStream,
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _flags: TcpFlagOverrides,
    _ipv4_identification: Option<u16>,
) -> io::Result<()> {
    unsupported()
}

#[allow(clippy::too_many_arguments)]
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_flagged_tcp_payload(
    _stream: &TcpStream,
    _payload: &[u8],
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _md5sig: bool,
    _flags: TcpFlagOverrides,
    _ipv4_identification: Option<u16>,
) -> io::Result<()> {
    unsupported()
}

#[allow(clippy::too_many_arguments)]
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_seqovl_tcp(
    _stream: &TcpStream,
    _real_chunk: &[u8],
    _fake_prefix: &[u8],
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _md5sig: bool,
    _flags: TcpFlagOverrides,
    _ipv4_identification: Option<u16>,
) -> io::Result<()> {
    unsupported()
}

#[allow(clippy::too_many_arguments)]
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
    _ipv4_identifications: &[u16],
) -> io::Result<()> {
    unsupported()
}

#[allow(clippy::too_many_arguments)]
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_ordered_tcp_segments(
    _stream: &TcpStream,
    _segments: &[OrderedTcpSegment<'_>],
    _original_payload_len: usize,
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _md5sig: bool,
    _timestamp_delta_ticks: Option<i32>,
    _ipv4_identifications: &[u16],
    _wait: TcpStageWait,
) -> io::Result<()> {
    unsupported()
}

#[allow(clippy::too_many_arguments)]
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
    _ipv4_identification: Option<u16>,
) -> io::Result<()> {
    unsupported()
}

#[allow(clippy::too_many_arguments)]
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
    _ipv4_identification: Option<u16>,
) -> io::Result<()> {
    unsupported()
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
fn unsupported() -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}
