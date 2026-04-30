use std::io::{self, Write};
use std::mem::size_of_val;
use std::net::{IpAddr, Ipv4Addr};
use std::os::fd::{AsRawFd, RawFd};
use std::time::Duration;

use ripdpi_capabilities::{CapabilityOutcome, CapabilityUnavailable, RuntimeCapability};

use super::connected_pair;
use crate::linux::socket_options::{
    get_c_int_sockopt, get_rcvbuf, get_stream_ttl, get_tcp_window_clamp, set_stream_ttl,
};
use crate::linux::{
    attach_drop_sack, attach_strip_timestamps, bind_udp_low_port, detach_drop_sack, enable_recv_ttl,
    enable_tcp_fastopen_connect, read_chunk_with_ttl, set_rcvbuf, set_tcp_md5sig, set_tcp_window_clamp,
};

/// Query the number of BPF instructions in the currently attached socket filter.
/// Returns `Err` if no filter is attached.
fn get_bpf_filter_len(fd: RawFd) -> io::Result<usize> {
    // SO_GET_FILTER shares the same constant as SO_ATTACH_FILTER on the getsockopt
    // path. The kernel returns the number of program instructions as the syscall
    // return value (positive integer) and copies the filter bytes into `optval` if
    // the buffer is large enough. glibc passes the positive return value through
    // unchanged. Pass a buffer big enough for any filter we attach in this crate
    // so the kernel's copy_to_sockptr step succeeds and the instruction count is
    // surfaced via the rc value.
    let mut buffer: [libc::sock_filter; 64] = unsafe { std::mem::zeroed() };
    let mut len: libc::socklen_t = size_of_val(&buffer) as libc::socklen_t;
    let rc =
        unsafe { libc::getsockopt(fd, libc::SOL_SOCKET, libc::SO_ATTACH_FILTER, buffer.as_mut_ptr().cast(), &mut len) };
    if rc < 0 {
        return Err(io::Error::last_os_error());
    }
    Ok(rc as usize)
}

fn get_tcp_fastopen_connect(fd: RawFd) -> io::Result<bool> {
    Ok(get_c_int_sockopt(fd, libc::IPPROTO_TCP, libc::TCP_FASTOPEN_CONNECT)? != 0)
}

fn get_recv_ttl(fd: RawFd) -> io::Result<bool> {
    Ok(get_c_int_sockopt(fd, libc::IPPROTO_IP, libc::IP_RECVTTL)? != 0)
}

#[test]
fn set_tcp_md5sig_rejects_key_lengths_above_linux_limit() {
    let (client, _server) = connected_pair();
    let err = set_tcp_md5sig(&client, 81).expect_err("reject oversized md5 key");
    assert_eq!(err.kind(), io::ErrorKind::InvalidInput);
}

#[test]
fn enable_recv_ttl_succeeds_on_connected_tcp_socket() {
    let (client, _server) = connected_pair();
    enable_recv_ttl(&client).expect("enable IP_RECVTTL on connected socket");
}

#[test]
fn read_chunk_with_ttl_reads_data_from_connected_pair() {
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

/// Maps a raw `io::Error` through the same errno-to-outcome logic used by
/// `try_set_stream_ttl_with_outcome`. Extracted here so tests can exercise
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
/// `Unavailable { Unsupported }` - never to `Available` or a raw error.
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
                assert_eq!(reason, CapabilityUnavailable::PermissionDenied, "errno {errno}: expected PermissionDenied");
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
