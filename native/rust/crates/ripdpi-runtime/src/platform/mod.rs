use std::collections::HashMap;
use std::io;
use std::net::SocketAddrV4;
use std::net::{IpAddr, SocketAddr, TcpStream, UdpSocket};
use std::os::fd::{AsRawFd, RawFd};
use std::sync::OnceLock;
use std::time::Duration;

use crate::sync::Mutex;
use ripdpi_config::IpIdMode;
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

static SEQOVL_SUPPORTED: OnceLock<bool> = OnceLock::new();
#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
static IPV4_ID_ALLOCATOR: OnceLock<Mutex<Ipv4IdAllocator>> = OnceLock::new();

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

// ---------------------------------------------------------------------------
// Typed capability surface (Phase 2 slice 2.1 — additive only)
// ---------------------------------------------------------------------------

/// A discrete runtime capability that the engine can probe and report.
///
/// Variants map one-to-one to testable platform features; the string ids
/// returned by `as_str()` are stable for telemetry / serialization.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum RuntimeCapability {
    /// Ability to set IP TTL on outbound TCP packets via raw sockets.
    TtlWrite,
    /// Ability to send fake TCP segments via a raw socket (non-root path).
    RawTcpFakeSend,
    /// Ability to send IP-fragmented UDP via a raw socket (non-root path).
    RawUdpFragmentation,
    /// Ability to create a replacement (protected) socket for the VPN path.
    ReplacementSocket,
    /// Root-helper process is reachable and authenticated.
    RootHelperAvailable,
    /// Android VPN protect callback is wired up and callable.
    VpnProtectCallback,
    /// Socket can be bound to a specific network interface.
    NetworkBinding,
}

impl RuntimeCapability {
    /// Returns a stable, lowercase-snake-case identifier suitable for
    /// telemetry keys and JSON field names.
    pub fn as_str(self) -> &'static str {
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

/// Reason a capability is definitively unavailable.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum CapabilityUnavailable {
    /// The capability has not been probed yet.
    NotProbed,
    /// The platform does not support this capability at all.
    Unsupported,
    /// The process lacks the required permissions.
    PermissionDenied,
    /// The root-helper binary is missing or unreachable.
    MissingRootHelper,
    /// The capability is known but not yet implemented on this platform.
    NotImplemented,
}

/// Result of probing a single runtime capability.
///
/// `T` is the payload type when the capability is `Available` — for boolean
/// capabilities this is `bool`, for richer probes it may be a struct.
///
/// `io::Error` is intentionally not stored directly because it does not impl
/// `Clone` or `PartialEq`; the human-readable message is captured instead.
#[derive(Debug, Clone)]
pub enum CapabilityOutcome<T> {
    /// The capability is present and usable; `T` holds the probed state.
    Available(T),
    /// The capability is definitively unavailable for the given reason.
    Unavailable {
        capability: RuntimeCapability,
        reason: CapabilityUnavailable,
    },
    /// Probing itself failed with a transient or unexpected error.
    ProbeFailed {
        capability: RuntimeCapability,
        /// Human-readable error message (not the raw `io::Error`).
        error: String,
    },
}

impl<T> CapabilityOutcome<T> {
    /// Returns `true` only when the capability is `Available`.
    pub fn is_available(&self) -> bool {
        matches!(self, Self::Available(_))
    }

    /// Returns the `RuntimeCapability` tag for `Unavailable` and `ProbeFailed`
    /// variants; returns `None` for `Available` (the tag is not stored there).
    pub fn capability(&self) -> Option<RuntimeCapability> {
        match self {
            Self::Available(_) => None,
            Self::Unavailable { capability, .. } => Some(*capability),
            Self::ProbeFailed { capability, .. } => Some(*capability),
        }
    }

    /// Consumes the outcome and returns `Some(T)` if `Available`, else `None`.
    pub fn take(self) -> Option<T> {
        match self {
            Self::Available(v) => Some(v),
            _ => None,
        }
    }
}

// ---------------------------------------------------------------------------

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
struct Ipv4FlowKey {
    source: SocketAddrV4,
    target: SocketAddrV4,
}

#[derive(Debug, Default)]
#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
struct Ipv4IdAllocator {
    next_seq_by_flow: HashMap<Ipv4FlowKey, u16>,
    rnd_state: u32,
}

#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
impl Ipv4IdAllocator {
    fn reserve(&mut self, source: SocketAddrV4, target: SocketAddrV4, mode: IpIdMode, count: usize) -> Vec<u16> {
        match mode {
            IpIdMode::Seq | IpIdMode::SeqGroup => {
                let key = Ipv4FlowKey { source, target };
                let next = self.next_seq_by_flow.entry(key).or_insert(1);
                let mut ids = Vec::with_capacity(count);
                for _ in 0..count {
                    let current = *next;
                    ids.push(current);
                    *next = advance_ipv4_identification(current);
                }
                ids
            }
            IpIdMode::Rnd => (0..count).map(|_| self.next_random_non_zero()).collect(),
            IpIdMode::Zero => vec![0; count],
        }
    }

    fn next_random_non_zero(&mut self) -> u16 {
        if self.rnd_state == 0 {
            self.rnd_state = 0x9e37_79b9;
        }
        loop {
            self.rnd_state ^= self.rnd_state << 13;
            self.rnd_state ^= self.rnd_state >> 17;
            self.rnd_state ^= self.rnd_state << 5;
            let candidate = (self.rnd_state & u32::from(u16::MAX)) as u16;
            if candidate != 0 {
                return candidate;
            }
        }
    }
}

#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
fn advance_ipv4_identification(value: u16) -> u16 {
    if value == u16::MAX {
        1
    } else {
        value + 1
    }
}

#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
fn reserve_ipv4_identifications(
    source: SocketAddr,
    target: SocketAddr,
    mode: Option<IpIdMode>,
    count: usize,
) -> Vec<u16> {
    let Some(mode) = mode else {
        return Vec::new();
    };
    let (SocketAddr::V4(source), SocketAddr::V4(target)) = (source, target) else {
        return Vec::new();
    };
    let allocator = IPV4_ID_ALLOCATOR.get_or_init(|| Mutex::new(Ipv4IdAllocator::default()));
    allocator.lock().map(|mut guard| guard.reserve(source, target, mode, count)).unwrap_or_default()
}

#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
fn reserve_stream_ipv4_identifications(
    stream: &TcpStream,
    mode: Option<IpIdMode>,
    count: usize,
) -> io::Result<Vec<u16>> {
    Ok(reserve_ipv4_identifications(stream.local_addr()?, stream.peer_addr()?, mode, count))
}

#[cfg_attr(not(any(target_os = "linux", target_os = "android")), allow(dead_code))]
fn reserve_udp_ipv4_identifications(
    socket: &UdpSocket,
    target: SocketAddr,
    mode: Option<IpIdMode>,
    count: usize,
) -> io::Result<Vec<u16>> {
    Ok(reserve_ipv4_identifications(socket.local_addr()?, target, mode, count))
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
pub fn send_fake_rst_reserved(
    stream: &TcpStream,
    default_ttl: u8,
    protect_path: Option<&str>,
    flags: TcpFlagOverrides,
    ipv4_identification: Option<u16>,
) -> io::Result<()> {
    if let Some(result) =
        root_helper::with_root_helper(|h| h.send_fake_rst(stream.as_raw_fd(), default_ttl, flags, ipv4_identification))
    {
        return result;
    }
    linux::send_fake_rst(stream, default_ttl, protect_path, flags, ipv4_identification)
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_fake_rst(
    stream: &TcpStream,
    default_ttl: u8,
    protect_path: Option<&str>,
    flags: TcpFlagOverrides,
    ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    let ipv4_identification = reserve_stream_ipv4_identifications(stream, ip_id_mode, 1)?.into_iter().next();
    send_fake_rst_reserved(stream, default_ttl, protect_path, flags, ipv4_identification)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_fake_rst(
    _stream: &TcpStream,
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _flags: TcpFlagOverrides,
    _ip_id_mode: Option<IpIdMode>,
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

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_fake_tcp(
    stream: &TcpStream,
    original_prefix: &[u8],
    fake_prefix: &[u8],
    ttl: u8,
    md5sig: bool,
    default_ttl: u8,
    mut options: FakeTcpOptions<'_>,
    ip_id_mode: Option<IpIdMode>,
    wait: TcpStageWait,
) -> io::Result<()> {
    let source = stream.local_addr()?;
    let target = stream.peer_addr()?;
    let supports_ipv4_ids = matches!((source, target), (SocketAddr::V4(_), SocketAddr::V4(_)));
    let require_raw_path = supports_ipv4_ids && ip_id_mode.is_some();
    let force_raw_original = matches!(ip_id_mode, Some(IpIdMode::SeqGroup)) && supports_ipv4_ids;
    let packet_count = usize::from(!fake_prefix.is_empty())
        + usize::from(options.secondary_fake_prefix.is_some_and(|payload| !payload.is_empty()))
        + usize::from(force_raw_original || !options.orig_flags.is_empty());
    let ids = if require_raw_path {
        reserve_ipv4_identifications(source, target, ip_id_mode, packet_count)
    } else {
        Vec::new()
    };
    options.require_raw_path = require_raw_path;
    options.force_raw_original = force_raw_original;
    options.ipv4_identifications = ids;
    linux::send_fake_tcp(stream, original_prefix, fake_prefix, ttl, md5sig, default_ttl, options, wait)
}

#[allow(clippy::too_many_arguments)]
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_fake_tcp(
    _stream: &TcpStream,
    _original_prefix: &[u8],
    _fake_prefix: &[u8],
    _ttl: u8,
    _md5sig: bool,
    _default_ttl: u8,
    _options: FakeTcpOptions<'_>,
    _ip_id_mode: Option<IpIdMode>,
    _wait: TcpStageWait,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_ordered_tcp_segments_reserved(
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
pub fn send_ordered_tcp_segments(
    stream: &TcpStream,
    segments: &[OrderedTcpSegment<'_>],
    original_payload_len: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    timestamp_delta_ticks: Option<i32>,
    ip_id_mode: Option<IpIdMode>,
    wait: TcpStageWait,
) -> io::Result<()> {
    let source = stream.local_addr()?;
    let target = stream.peer_addr()?;
    let ipv4_identifications = reserve_ipv4_identifications(source, target, ip_id_mode, segments.len());
    if let Some(result) = root_helper::with_root_helper(|h| {
        let res = h.send_ordered_tcp_segments(
            stream.as_raw_fd(),
            segments,
            original_payload_len,
            default_ttl,
            md5sig,
            timestamp_delta_ticks,
            &ipv4_identifications,
            wait,
        )?;
        if let Some(replacement_fd) = res {
            swap_replacement_fd(stream.as_raw_fd(), replacement_fd)?;
        }
        Ok(())
    }) {
        return result;
    }
    send_ordered_tcp_segments_reserved(
        stream,
        segments,
        original_payload_len,
        default_ttl,
        protect_path,
        md5sig,
        timestamp_delta_ticks,
        &ipv4_identifications,
        wait,
    )
}

#[allow(clippy::too_many_arguments)]
#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_ordered_tcp_segments_reserved(
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
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
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
    _ip_id_mode: Option<IpIdMode>,
    _wait: TcpStageWait,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_flagged_tcp_payload_reserved(
    stream: &TcpStream,
    payload: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: TcpFlagOverrides,
    ipv4_identification: Option<u16>,
) -> io::Result<()> {
    if let Some(result) = root_helper::with_root_helper(|h| {
        let res =
            h.send_flagged_tcp_payload(stream.as_raw_fd(), payload, default_ttl, md5sig, flags, ipv4_identification)?;
        if let Some(replacement_fd) = res {
            swap_replacement_fd(stream.as_raw_fd(), replacement_fd)?;
        }
        Ok(())
    }) {
        return result;
    }
    linux::send_flagged_tcp_payload(stream, payload, default_ttl, protect_path, md5sig, flags, ipv4_identification)
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_flagged_tcp_payload(
    stream: &TcpStream,
    payload: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: TcpFlagOverrides,
    ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    let ipv4_identification = reserve_stream_ipv4_identifications(stream, ip_id_mode, 1)?.into_iter().next();
    send_flagged_tcp_payload_reserved(stream, payload, default_ttl, protect_path, md5sig, flags, ipv4_identification)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_flagged_tcp_payload(
    _stream: &TcpStream,
    _payload: &[u8],
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _md5sig: bool,
    _flags: TcpFlagOverrides,
    _ip_id_mode: Option<IpIdMode>,
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

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_ip_fragmented_udp_reserved(
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
    if let Some(result) = root_helper::with_root_helper(|h| {
        h.send_ip_fragmented_udp(
            upstream.as_raw_fd(),
            target,
            payload,
            split_offset,
            default_ttl,
            disorder,
            ipv4_identification,
        )
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
    ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    let ipv4_identification = reserve_udp_ipv4_identifications(upstream, target, ip_id_mode, 1)?.into_iter().next();
    send_ip_fragmented_udp_reserved(
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
pub fn send_ip_fragmented_udp(
    _upstream: &UdpSocket,
    _target: SocketAddr,
    _payload: &[u8],
    _split_offset: usize,
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _disorder: bool,
    _ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    _ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_ip_fragmented_tcp_reserved(
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
    if let Some(result) = root_helper::with_root_helper(|h| {
        let res = h.send_ip_fragmented_tcp(
            stream.as_raw_fd(),
            payload,
            split_offset,
            default_ttl,
            disorder,
            flags,
            ipv4_identification,
        )?;
        if let Some(replacement_fd) = res {
            swap_replacement_fd(stream.as_raw_fd(), replacement_fd)?;
        }
        Ok(())
    }) {
        return result;
    }
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
pub fn send_ip_fragmented_tcp(
    stream: &TcpStream,
    payload: &[u8],
    split_offset: usize,
    default_ttl: u8,
    protect_path: Option<&str>,
    disorder: bool,
    ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
    flags: TcpFlagOverrides,
    ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    let ipv4_identification = reserve_stream_ipv4_identifications(stream, ip_id_mode, 1)?.into_iter().next();
    send_ip_fragmented_tcp_reserved(
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
    _ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[allow(clippy::too_many_arguments)]
#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_multi_disorder_tcp_reserved(
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
    if let Some(result) = root_helper::with_root_helper(|h| {
        let res = h.send_multi_disorder_tcp(
            stream.as_raw_fd(),
            payload,
            segments,
            default_ttl,
            inter_segment_delay_ms,
            md5sig,
            flags,
            ipv4_identifications,
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
        ipv4_identifications,
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
    ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    let ipv4_identifications = reserve_stream_ipv4_identifications(stream, ip_id_mode, segments.len())?;
    send_multi_disorder_tcp_reserved(
        stream,
        payload,
        segments,
        default_ttl,
        protect_path,
        inter_segment_delay_ms,
        md5sig,
        flags,
        &ipv4_identifications,
    )
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
    _ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_seqovl_tcp_reserved(
    stream: &TcpStream,
    real_chunk: &[u8],
    fake_prefix: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: TcpFlagOverrides,
    ipv4_identification: Option<u16>,
) -> io::Result<()> {
    if let Some(result) = root_helper::with_root_helper(|h| {
        let res = h.send_seqovl_tcp(
            stream.as_raw_fd(),
            real_chunk,
            fake_prefix,
            default_ttl,
            md5sig,
            flags,
            ipv4_identification,
        )?;
        if let Some(replacement_fd) = res {
            swap_replacement_fd(stream.as_raw_fd(), replacement_fd)?;
        }
        Ok(())
    }) {
        return result;
    }
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

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn send_seqovl_tcp(
    stream: &TcpStream,
    real_chunk: &[u8],
    fake_prefix: &[u8],
    default_ttl: u8,
    protect_path: Option<&str>,
    md5sig: bool,
    flags: TcpFlagOverrides,
    ip_id_mode: Option<IpIdMode>,
) -> io::Result<()> {
    let ipv4_identification = reserve_stream_ipv4_identifications(stream, ip_id_mode, 1)?.into_iter().next();
    send_seqovl_tcp_reserved(
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

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn send_seqovl_tcp(
    _stream: &TcpStream,
    _real_chunk: &[u8],
    _fake_prefix: &[u8],
    _default_ttl: u8,
    _protect_path: Option<&str>,
    _md5sig: bool,
    _flags: TcpFlagOverrides,
    _ip_id_mode: Option<IpIdMode>,
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
    use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
    use std::ptr;

    use ripdpi_config::IpIdMode;

    use super::{extract_scm_rights_fd, read_unaligned_raw_fd, reserve_ipv4_identifications, Ipv4IdAllocator};

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

    #[test]
    fn ipv4_id_allocator_seq_is_contiguous_per_flow() {
        let source = SocketAddrV4::new(Ipv4Addr::new(192, 0, 2, 10), 40000);
        let target = SocketAddrV4::new(Ipv4Addr::new(198, 51, 100, 20), 443);
        let mut allocator = Ipv4IdAllocator::default();

        assert_eq!(allocator.reserve(source, target, IpIdMode::Seq, 3), vec![1, 2, 3]);
        assert_eq!(allocator.reserve(source, target, IpIdMode::Seq, 2), vec![4, 5]);
    }

    #[test]
    fn ipv4_id_allocator_seqgroup_uses_same_sequential_scheme() {
        let source = SocketAddrV4::new(Ipv4Addr::new(192, 0, 2, 11), 40001);
        let target = SocketAddrV4::new(Ipv4Addr::new(198, 51, 100, 21), 443);
        let mut allocator = Ipv4IdAllocator::default();

        assert_eq!(allocator.reserve(source, target, IpIdMode::SeqGroup, 2), vec![1, 2]);
        assert_eq!(allocator.reserve(source, target, IpIdMode::SeqGroup, 1), vec![3]);
    }

    #[test]
    fn ipv4_id_allocator_zero_returns_zeroes() {
        let source = SocketAddrV4::new(Ipv4Addr::new(192, 0, 2, 12), 40002);
        let target = SocketAddrV4::new(Ipv4Addr::new(198, 51, 100, 22), 443);
        let mut allocator = Ipv4IdAllocator::default();

        assert_eq!(allocator.reserve(source, target, IpIdMode::Zero, 3), vec![0, 0, 0]);
    }

    #[test]
    fn ipv4_id_allocator_rnd_returns_non_zero_values() {
        let source = SocketAddrV4::new(Ipv4Addr::new(192, 0, 2, 13), 40003);
        let target = SocketAddrV4::new(Ipv4Addr::new(198, 51, 100, 23), 443);
        let mut allocator = Ipv4IdAllocator::default();

        let values = allocator.reserve(source, target, IpIdMode::Rnd, 8);

        assert_eq!(values.len(), 8);
        assert!(values.iter().all(|value| *value != 0));
    }

    #[test]
    fn reserve_ipv4_identifications_skips_ipv6_flows() {
        let source = SocketAddr::from((Ipv4Addr::new(192, 0, 2, 14), 40004));
        let target = SocketAddr::from(([0u16, 0, 0, 0, 0, 0, 0, 1], 443));

        assert!(reserve_ipv4_identifications(source, target, Some(IpIdMode::SeqGroup), 2).is_empty());
    }

    // -----------------------------------------------------------------------
    // RuntimeCapability / CapabilityOutcome tests
    // -----------------------------------------------------------------------

    use super::{CapabilityOutcome, CapabilityUnavailable, RuntimeCapability};

    #[test]
    fn runtime_capability_as_str_stable() {
        assert_eq!(RuntimeCapability::TtlWrite.as_str(), "ttl_write");
        assert_eq!(RuntimeCapability::RawTcpFakeSend.as_str(), "raw_tcp_fake_send");
        assert_eq!(RuntimeCapability::RawUdpFragmentation.as_str(), "raw_udp_fragmentation");
        assert_eq!(RuntimeCapability::ReplacementSocket.as_str(), "replacement_socket");
        assert_eq!(RuntimeCapability::RootHelperAvailable.as_str(), "root_helper_available");
        assert_eq!(RuntimeCapability::VpnProtectCallback.as_str(), "vpn_protect_callback");
        assert_eq!(RuntimeCapability::NetworkBinding.as_str(), "network_binding");
    }

    #[test]
    fn capability_outcome_is_available() {
        let avail: CapabilityOutcome<bool> = CapabilityOutcome::Available(true);
        assert!(avail.is_available());

        let unavail: CapabilityOutcome<bool> = CapabilityOutcome::Unavailable {
            capability: RuntimeCapability::TtlWrite,
            reason: CapabilityUnavailable::Unsupported,
        };
        assert!(!unavail.is_available());

        let failed: CapabilityOutcome<bool> = CapabilityOutcome::ProbeFailed {
            capability: RuntimeCapability::RawTcpFakeSend,
            error: "test error".to_owned(),
        };
        assert!(!failed.is_available());
    }

    #[test]
    fn capability_outcome_take() {
        let avail: CapabilityOutcome<u32> = CapabilityOutcome::Available(42);
        assert_eq!(avail.take(), Some(42));

        let unavail: CapabilityOutcome<u32> = CapabilityOutcome::Unavailable {
            capability: RuntimeCapability::NetworkBinding,
            reason: CapabilityUnavailable::PermissionDenied,
        };
        assert_eq!(unavail.take(), None);

        let failed: CapabilityOutcome<u32> = CapabilityOutcome::ProbeFailed {
            capability: RuntimeCapability::RootHelperAvailable,
            error: "oops".to_owned(),
        };
        assert_eq!(failed.take(), None);
    }

    #[test]
    fn capability_outcome_capability_accessor() {
        let avail: CapabilityOutcome<bool> = CapabilityOutcome::Available(true);
        assert_eq!(avail.capability(), None);

        let unavail: CapabilityOutcome<bool> = CapabilityOutcome::Unavailable {
            capability: RuntimeCapability::VpnProtectCallback,
            reason: CapabilityUnavailable::NotProbed,
        };
        assert_eq!(unavail.capability(), Some(RuntimeCapability::VpnProtectCallback));

        let failed: CapabilityOutcome<bool> = CapabilityOutcome::ProbeFailed {
            capability: RuntimeCapability::ReplacementSocket,
            error: "err".to_owned(),
        };
        assert_eq!(failed.capability(), Some(RuntimeCapability::ReplacementSocket));
    }
}
