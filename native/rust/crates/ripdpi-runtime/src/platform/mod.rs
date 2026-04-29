use std::io;
use std::net::{IpAddr, SocketAddr, TcpStream, UdpSocket};
use std::os::fd::{AsRawFd, RawFd};
use std::sync::OnceLock;
use std::time::Duration;

use ripdpi_desync::TcpSegmentHint;

mod capabilities;
mod experimental_tier3;
mod fake_send;
mod ip_fragmentation;
mod ipv4_ids;
#[cfg(any(target_os = "linux", target_os = "android"))]
pub(crate) mod linux;
pub mod protect;
#[cfg(any(target_os = "linux", target_os = "android"))]
pub mod root_helper;
#[cfg(any(target_os = "linux", target_os = "android"))]
pub mod root_helper_client;

pub use self::capabilities::{
    detect_default_ttl, try_set_stream_ttl_with_outcome, CapabilityOutcome, CapabilityUnavailable, RuntimeCapability,
};
// Tier-3 primitives are exported pub from the runtime lib so external crates
// (e.g. integration tests, the privileged-ops staging crate) can pin against
// the staging API surface; treat as `pub(crate)` semantically until wired
// through `DesyncMode` or UI. See
// docs/architecture/README.md#desync-and-relay-rules.
pub use experimental_tier3::{
    recv_icmp_wrapped_udp, send_icmp_wrapped_udp, send_syn_hide_tcp, IcmpWrappedUdpRecvFilter, IcmpWrappedUdpRole,
    IcmpWrappedUdpSpec, ReceivedIcmpWrappedUdp, SynHideMarkerKind, SynHideTcpSpec,
};
pub use fake_send::{
    send_fake_rst, send_fake_rst_reserved, send_fake_tcp, send_flagged_tcp_payload, send_flagged_tcp_payload_reserved,
    send_ordered_tcp_segments, send_ordered_tcp_segments_reserved, send_seqovl_tcp, send_seqovl_tcp_reserved,
};
pub use ip_fragmentation::{
    probe_ip_fragmentation_capabilities, send_ip_fragmented_tcp, send_ip_fragmented_tcp_reserved,
    send_ip_fragmented_udp, send_ip_fragmented_udp_reserved, send_multi_disorder_tcp, send_multi_disorder_tcp_reserved,
};

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

    use super::ipv4_ids::{reserve_ipv4_identifications, Ipv4IdAllocator};
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

    // -----------------------------------------------------------------------
    // VpnProtectCallback unavailable (slice 2.5 regression)
    // -----------------------------------------------------------------------

    /// Maps the result of `protect_socket_via_callback` when no callback is
    /// registered to a typed `CapabilityOutcome`.  This mirrors what production
    /// code will do once slice 2.6 wires the emitter path.
    fn vpn_protect_outcome_when_unregistered() -> CapabilityOutcome<()> {
        use ripdpi_native_protect::protect_socket_via_callback;
        match protect_socket_via_callback(-1) {
            Ok(()) => CapabilityOutcome::Available(()),
            Err(err) if err.kind() == std::io::ErrorKind::NotConnected => CapabilityOutcome::Unavailable {
                capability: RuntimeCapability::VpnProtectCallback,
                reason: CapabilityUnavailable::NotProbed,
            },
            Err(err) => CapabilityOutcome::ProbeFailed {
                capability: RuntimeCapability::VpnProtectCallback,
                error: err.to_string(),
            },
        }
    }

    /// Regression (slice 2.5): when no VPN protect callback is registered,
    /// the outcome is `Unavailable { VpnProtectCallback, NotProbed }` — never
    /// `Available` and never a raw `io::Error` propagated upstream.
    #[test]
    fn vpn_protect_callback_absent_produces_unavailable_outcome() {
        use std::sync::Mutex;

        // Serialise against other tests that touch the global protect callback.
        static PROTECT_TEST_MUTEX: Mutex<()> = Mutex::new(());
        let _guard = PROTECT_TEST_MUTEX.lock().expect("protect test mutex");

        ripdpi_native_protect::unregister_protect_callback();
        assert!(!ripdpi_native_protect::has_protect_callback(), "precondition: no callback registered");

        let outcome = vpn_protect_outcome_when_unregistered();
        match outcome {
            CapabilityOutcome::Unavailable { capability, reason } => {
                assert_eq!(capability, RuntimeCapability::VpnProtectCallback);
                assert_eq!(reason, CapabilityUnavailable::NotProbed);
            }
            other => panic!("expected Unavailable{{VpnProtectCallback, NotProbed}}, got {other:?}"),
        }
    }
}
