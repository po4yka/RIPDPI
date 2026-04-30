use std::io;
use std::net::{IpAddr, SocketAddr, TcpStream, UdpSocket};
use std::os::fd::AsRawFd;
use std::sync::OnceLock;
use std::time::Duration;

use ripdpi_desync::TcpSegmentHint;

mod capabilities;
mod experimental_tier3;
mod fake_send;
mod ip_fragmentation;
mod ipv4_ids;
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
pub use ripdpi_privileged_ops::{
    FakeTcpOptions, IpFragmentationCapabilities, OrderedTcpSegment, TcpActivationState, TcpFlagOverrides,
    TcpPayloadSegment, TcpStageWait,
};

static SEQOVL_SUPPORTED: OnceLock<bool> = OnceLock::new();

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
    ripdpi_privileged_ops::enable_tcp_fastopen_connect(socket)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn enable_tcp_fastopen_connect<T>(_socket: &T) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn set_tcp_md5sig(stream: &TcpStream, key_len: u16) -> io::Result<()> {
    ripdpi_privileged_ops::set_tcp_md5sig(stream, key_len)
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
    ripdpi_privileged_ops::protect_socket(socket, path)
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
    ripdpi_privileged_ops::original_dst(stream)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn original_dst(_stream: &TcpStream) -> io::Result<SocketAddr> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn attach_drop_sack(stream: &TcpStream) -> io::Result<()> {
    ripdpi_privileged_ops::attach_drop_sack(stream)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn attach_drop_sack(_stream: &TcpStream) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn detach_drop_sack(stream: &TcpStream) -> io::Result<()> {
    ripdpi_privileged_ops::detach_drop_sack(stream)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn detach_drop_sack(_stream: &TcpStream) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn set_tcp_window_clamp(stream: &TcpStream, size: u32) -> io::Result<()> {
    ripdpi_privileged_ops::set_tcp_window_clamp(stream, size)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn set_tcp_window_clamp(_stream: &TcpStream, _size: u32) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn set_rcvbuf(fd: &impl AsRawFd, size: u32) -> io::Result<()> {
    ripdpi_privileged_ops::set_rcvbuf(fd, size)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn set_rcvbuf(_fd: &impl AsRawFd, _size: u32) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn attach_strip_timestamps(stream: &TcpStream) -> io::Result<()> {
    ripdpi_privileged_ops::attach_strip_timestamps(stream)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn attach_strip_timestamps(_stream: &TcpStream) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn bind_udp_low_port(socket: &UdpSocket, local_ip: IpAddr, max_port: u16) -> io::Result<u16> {
    ripdpi_privileged_ops::bind_udp_low_port(socket, local_ip, max_port)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn bind_udp_low_port(_socket: &UdpSocket, _local_ip: IpAddr, _max_port: u16) -> io::Result<u16> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn wait_tcp_stage(stream: &TcpStream, wait_send: bool, await_interval: Duration) -> io::Result<()> {
    ripdpi_privileged_ops::wait_tcp_stage(stream, wait_send, await_interval)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn wait_tcp_stage(_stream: &TcpStream, _wait_send: bool, _await_interval: Duration) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn tcp_segment_hint(stream: &TcpStream) -> io::Result<Option<TcpSegmentHint>> {
    ripdpi_privileged_ops::tcp_segment_hint(stream)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn tcp_segment_hint(_stream: &TcpStream) -> io::Result<Option<TcpSegmentHint>> {
    Ok(None)
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn tcp_activation_state(stream: &TcpStream) -> io::Result<Option<TcpActivationState>> {
    ripdpi_privileged_ops::tcp_activation_state(stream)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn tcp_activation_state(_stream: &TcpStream) -> io::Result<Option<TcpActivationState>> {
    Ok(None)
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn tcp_round_trip_time_ms(stream: &TcpStream) -> io::Result<Option<u64>> {
    ripdpi_privileged_ops::tcp_round_trip_time_ms(stream)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn tcp_round_trip_time_ms(_stream: &TcpStream) -> io::Result<Option<u64>> {
    Ok(None)
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn tcp_total_retransmissions<T: AsRawFd>(socket: &T) -> io::Result<Option<u32>> {
    ripdpi_privileged_ops::tcp_total_retransmissions(socket)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn tcp_total_retransmissions<T: AsRawFd>(_socket: &T) -> io::Result<Option<u32>> {
    Ok(None)
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn enable_recv_ttl(stream: &TcpStream) -> io::Result<()> {
    ripdpi_privileged_ops::enable_recv_ttl(stream)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn enable_recv_ttl(_stream: &TcpStream) -> io::Result<()> {
    Ok(()) // best-effort; no-op on non-Linux
}

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn read_chunk_with_ttl(stream: &TcpStream, buf: &mut [u8]) -> io::Result<(usize, Option<u8>)> {
    ripdpi_privileged_ops::read_chunk_with_ttl(stream, buf)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn read_chunk_with_ttl(stream: &TcpStream, buf: &mut [u8]) -> io::Result<(usize, Option<u8>)> {
    use std::io::Read;
    Ok(((&*stream).read(buf)?, None))
}

#[cfg(test)]
mod tests {
    use std::net::{Ipv4Addr, SocketAddr, SocketAddrV4};
    use std::path::Path;

    use ripdpi_config::IpIdMode;

    use super::ipv4_ids::{reserve_ipv4_identifications, Ipv4IdAllocator};

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
    fn runtime_does_not_own_linux_platform_implementation() {
        let crate_root = Path::new(env!("CARGO_MANIFEST_DIR"));
        let forbidden_paths = [
            "src/platform/linux.rs",
            "src/platform/linux",
            "src/platform/linux/fake_send.rs",
            "src/platform/linux/ip_fragmentation.rs",
            "src/platform/linux/experimental_tier3.rs",
        ];

        for path in forbidden_paths {
            assert!(
                !crate_root.join(path).exists(),
                "privileged platform operations must live in ripdpi-privileged-ops, not {path}",
            );
        }
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
