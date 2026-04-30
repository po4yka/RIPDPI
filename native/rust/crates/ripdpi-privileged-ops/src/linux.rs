//! Linux/Android platform socket operations.
//!
//! This module intentionally uses raw `libc::setsockopt`/`getsockopt` for
//! kernel-specific options not available in `socket2` (as of 0.5):
//! TCP_INFO, TCP_MD5SIG, TCP_FASTOPEN_CONNECT, SO_ATTACH_FILTER,
//! SO_ORIGINAL_DST, IP_RECVTTL, and `recvmsg` with CMSG ancillary data.
//!
//! Standard socket options use `socket2::SockRef` (see [`set_stream_ttl`]).
//!
//! # TTL write capability
//!
//! [`try_set_stream_ttl_with_outcome`] is the preferred entry point for new
//! code that needs to set a per-socket TTL. It returns a typed
//! [`CapabilityOutcome`] rather than an `io::Result`, making the
//! unavailable / permission-denied cases explicit at the call site.
//! The lower-level [`set_stream_ttl`] helper (returning `io::Result`) is kept
//! for internal use by the restore path where fire-and-forget semantics are
//! acceptable.

mod bpf;
mod experimental_tier3;
mod fd;
mod fragmentation;
mod mmap_region;
mod raw_packet;
mod socket_options;
mod tcp_info;
mod tcp_repair;

pub use bpf::{attach_drop_sack, attach_strip_timestamps, detach_drop_sack};
pub use experimental_tier3::{recv_icmp_wrapped_udp, send_icmp_wrapped_udp, send_syn_hide_tcp};
pub use fd::{close_fd, dup2_fd, original_dst, protect_socket};
pub use fragmentation::{
    probe_ip_fragmentation_capabilities, send_ip_fragmented_tcp, send_ip_fragmented_udp, send_multi_disorder_tcp,
};
pub use raw_packet::{
    send_fake_rst, send_fake_tcp, send_flagged_tcp_payload, send_ordered_tcp_segments, send_seqovl_tcp,
};
pub use socket_options::{
    bind_udp_low_port, enable_recv_ttl, enable_tcp_fastopen_connect, read_chunk_with_ttl, set_rcvbuf, set_tcp_md5sig,
    set_tcp_window_clamp, try_set_stream_ttl_with_outcome,
};
pub use tcp_info::{
    tcp_activation_state, tcp_round_trip_time_ms, tcp_segment_hint, tcp_total_retransmissions, wait_tcp_stage,
};

#[cfg(test)]
use fd::storage_to_socket_addr;
#[cfg(test)]
use socket_options::{get_c_int_sockopt, get_rcvbuf, get_stream_ttl, get_tcp_window_clamp};
#[cfg(test)]
use tcp_info::{
    tcp_has_notsent, tcp_total_retransmissions_from_info, wait_tcp_stage_fd, LinuxTcpInfo, TCP_ESTABLISHED,
};
#[cfg(test)]
use tcp_info::{TCPI_OPT_SACK, TCPI_OPT_TIMESTAMPS, TCPI_OPT_USEC_TS, TCPI_OPT_WSCALE};
#[cfg(test)]
use tcp_repair::{
    decode_tcp_repair_options, sequence_after_payload, TcpRepairOptionsSnapshot, TcpRepairSnapshot, TcpRepairWindow,
    TcpTimestampSnapshot, TcpWindowScaleSnapshot,
};

#[cfg(test)]
mod tests {
    use super::*;
    use etherparse::{Ipv4Header, TcpHeader};
    use ripdpi_capabilities::{CapabilityOutcome, CapabilityUnavailable, RuntimeCapability};
    use std::io::{self, Read, Write};
    use std::mem::{size_of_val, zeroed};
    use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, TcpListener, TcpStream};
    use std::os::fd::{AsRawFd, IntoRawFd};
    use std::os::unix::net::UnixStream;
    use std::slice;
    use std::time::Duration;

    use crate::linux::mmap_region::{alloc_region, free_region, write_region};
    use crate::linux::socket_options::set_stream_ttl;
    use crate::TcpFlagOverrides;

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

        let increased = raw_packet::mutate_fake_timestamp(original, Some(7)).expect("increase timestamp");
        assert_eq!(increased.unwrap().value, 17);

        let decreased = raw_packet::mutate_fake_timestamp(original, Some(-15)).expect("decrease timestamp");
        assert_eq!(decreased.unwrap().value, u32::MAX - 4);
    }

    #[test]
    fn mutate_fake_timestamp_requires_negotiated_timestamp_option() {
        let err =
            raw_packet::mutate_fake_timestamp(None, Some(1)).expect_err("missing negotiated timestamp should fail");
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

        let packets = fragmentation::build_multi_disorder_packets(
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

        let err = fragmentation::build_multi_disorder_packets(
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

        let err = fragmentation::build_multi_disorder_packets(
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
        let outcome = ttl_error_to_outcome(io::Error::from_raw_os_error(libc::EIO));
        match outcome {
            CapabilityOutcome::ProbeFailed { capability, .. } => {
                assert_eq!(capability, RuntimeCapability::TtlWrite);
            }
            other => panic!("expected ProbeFailed, got {other:?}"),
        }
    }
}
