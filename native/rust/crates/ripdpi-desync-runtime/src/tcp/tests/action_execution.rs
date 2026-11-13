use super::*;

use crate::{TcpStrategyFamily, TcpTerminalReason};

#[test]
fn actions_write_only_no_strategy() {
    let (mut client, mut server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let actions = vec![DesyncAction::Write(b"hello".to_vec()), DesyncAction::Write(b"world".to_vec())];
    let result = execute_tcp_actions(
        &mut client,
        &actions,
        64,
        false,
        Duration::from_millis(10),
        None,
        &unavailable,
        false,
        None,
        None,
    );
    assert_eq!(result.unwrap(), 10);
    let mut buf = vec![0u8; 10];
    use std::io::Read;
    server.read_exact(&mut buf).expect("read");
    assert_eq!(&buf, b"helloworld");
}

#[test]
fn actions_write_with_strategy() {
    let (mut client, _server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let actions = vec![DesyncAction::Write(b"hello".to_vec())];
    let result = execute_tcp_actions(
        &mut client,
        &actions,
        64,
        false,
        Duration::from_millis(10),
        Some("split"),
        &unavailable,
        false,
        None,
        None,
    );
    assert_eq!(result.unwrap(), 5);
}

#[test]
fn action_error_carries_receipt_for_completed_steps_before_failure() {
    let (mut client, _server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let actions = vec![
        DesyncAction::Write(b"hello".to_vec()),
        DesyncAction::WriteIpFragmentedUdp {
            bytes: b"data".to_vec(),
            split_offset: 2,
            disorder: false,
            ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders::default(),
        },
    ];

    let err = execute_tcp_actions(
        &mut client,
        &actions,
        64,
        false,
        Duration::from_millis(10),
        Some("split"),
        &unavailable,
        false,
        None,
        None,
    )
    .expect_err("udp fragmentation should fail in tcp executor after the first write");

    match err {
        OutboundSendError::Transport { execution_receipt: Some(execution_receipt), .. } => {
            assert_eq!(execution_receipt.disposition, TcpExecutionDisposition::ExecutionFailed);
            assert_eq!(execution_receipt.effective_family, Some(TcpStrategyFamily::Split));
            assert_eq!(execution_receipt.attempted_actions, 2);
            assert_eq!(execution_receipt.completed_actions, 1);
            assert_eq!(execution_receipt.real_writes_committed, 1);
            assert_eq!(execution_receipt.payload_bytes_committed, 5);
            assert_eq!(execution_receipt.terminal_reason, Some(TcpTerminalReason::Transport));
        }
        OutboundSendError::Transport { execution_receipt: None, .. } => panic!("transport error missed receipt"),
        OutboundSendError::StrategyExecution { .. } => panic!("expected transport failure"),
    }
}

#[test]
fn actions_set_ttl_and_restore() {
    let (mut client, _server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let actions = vec![DesyncAction::SetTtl(42), DesyncAction::Write(b"x".to_vec()), DesyncAction::RestoreDefaultTtl];
    let result = execute_tcp_actions(
        &mut client,
        &actions,
        64,
        false,
        Duration::from_millis(10),
        Some("disorder"),
        &unavailable,
        false,
        None,
        None,
    );
    assert_eq!(result.unwrap(), 1);
}

#[test]
fn actions_set_ttl_auto_detect() {
    let (mut client, _server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let actions = vec![DesyncAction::SetTtl(1), DesyncAction::RestoreDefaultTtl];
    let result = execute_tcp_actions(
        &mut client,
        &actions,
        0,
        false,
        Duration::from_millis(10),
        Some("disorder"),
        &unavailable,
        false,
        None,
        None,
    );
    assert_eq!(result.unwrap(), 0);
}

#[test]
fn actions_write_urgent_no_strategy() {
    let (mut client, _server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let actions = vec![DesyncAction::WriteUrgent { prefix: b"ab".to_vec(), urgent_byte: b'!' }];
    let result = execute_tcp_actions(
        &mut client,
        &actions,
        64,
        false,
        Duration::from_millis(10),
        None,
        &unavailable,
        false,
        None,
        None,
    );
    assert_eq!(result.unwrap(), 2); // the urgent byte is not application data
}

#[test]
fn actions_write_urgent_with_strategy() {
    let (mut client, _server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let actions = vec![DesyncAction::WriteUrgent { prefix: b"ab".to_vec(), urgent_byte: b'!' }];
    let result = execute_tcp_actions(
        &mut client,
        &actions,
        64,
        false,
        Duration::from_millis(10),
        Some("oob"),
        &unavailable,
        false,
        None,
        None,
    );
    assert_eq!(result.unwrap(), 2);
}

// ipfrag2 fallback tests: on non-Linux, send_ip_fragmented_tcp returns
// Unsupported and the fallback path plain-writes the data.  On Linux the
// raw-socket call needs CAP_NET_RAW which CI runners lack.
#[test]
#[cfg(not(target_os = "linux"))]
fn actions_ipfrag2_fallback_with_strategy() {
    let (mut client, mut server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let actions = vec![DesyncAction::WriteIpFragmentedTcp {
        bytes: b"hello".to_vec(),
        split_offset: 2,
        disorder: false,
        ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders::default(),
    }];
    let result = execute_tcp_actions(
        &mut client,
        &actions,
        64,
        false,
        Duration::from_millis(10),
        Some("ipfrag2"),
        &unavailable,
        false,
        None,
        None,
    );
    assert_eq!(result.unwrap(), 5);
    let mut buf = vec![0u8; 5];
    use std::io::Read;
    server.read_exact(&mut buf).expect("read");
    assert_eq!(&buf, b"hello");
}

#[test]
#[cfg(not(target_os = "linux"))]
fn actions_ipfrag2_fallback_no_strategy() {
    let (mut client, mut server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let actions = vec![DesyncAction::WriteIpFragmentedTcp {
        bytes: b"world".to_vec(),
        split_offset: 2,
        disorder: false,
        ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders::default(),
    }];
    let result = execute_tcp_actions(
        &mut client,
        &actions,
        64,
        false,
        Duration::from_millis(10),
        None,
        &unavailable,
        false,
        None,
        None,
    );
    assert_eq!(result.unwrap(), 5);
    let mut buf = vec![0u8; 5];
    use std::io::Read;
    server.read_exact(&mut buf).expect("read");
    assert_eq!(&buf, b"world");
}

#[test]
fn actions_seqovl_fallback_to_split() {
    let (mut client, mut server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let actions = vec![DesyncAction::WriteSeqOverlap {
        real_chunk: b"ab".to_vec(),
        fake_prefix: b"xx".to_vec(),
        remainder: b"cd".to_vec(),
        flags: ripdpi_config::TcpFlagOverrides::disabled(),
    }];
    // On macOS, send_seqovl_tcp returns Unsupported -> fallback writes real_chunk + remainder
    let result = execute_tcp_actions(
        &mut client,
        &actions,
        64,
        false,
        Duration::from_millis(10),
        None,
        &unavailable,
        false,
        None,
        None,
    );
    assert_eq!(result.unwrap(), 4);
    let mut buf = vec![0u8; 4];
    use std::io::Read;
    server.read_exact(&mut buf).expect("read");
    assert_eq!(&buf, b"abcd");
}

#[test]
fn actions_udp_frag_rejects_in_tcp() {
    let (mut client, _server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let actions = vec![DesyncAction::WriteIpFragmentedUdp {
        bytes: b"data".to_vec(),
        split_offset: 2,
        disorder: false,
        ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders::default(),
    }];
    let err = execute_tcp_actions(
        &mut client,
        &actions,
        64,
        false,
        Duration::from_millis(10),
        None,
        &unavailable,
        false,
        None,
        None,
    )
    .unwrap_err();
    assert_eq!(err.kind(), io::ErrorKind::InvalidInput);
    assert!(err.to_string().contains("udp fragmentation action reached tcp executor"));
}

#[test]
fn actions_attach_detach_drop_sack_noop() {
    let (mut client, _server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let actions = vec![DesyncAction::AttachDropSack, DesyncAction::Write(b"x".to_vec()), DesyncAction::DetachDropSack];
    let result = execute_tcp_actions(
        &mut client,
        &actions,
        64,
        false,
        Duration::from_millis(10),
        None,
        &unavailable,
        false,
        None,
        None,
    );
    assert_eq!(result.unwrap(), 1);
}

#[test]
fn actions_window_clamp_ignored_on_unsupported() {
    let (mut client, _server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let actions =
        vec![DesyncAction::SetWindowClamp(1024), DesyncAction::Write(b"x".to_vec()), DesyncAction::RestoreWindowClamp];
    let result = execute_tcp_actions(
        &mut client,
        &actions,
        64,
        false,
        Duration::from_millis(10),
        None,
        &unavailable,
        false,
        None,
        None,
    );
    assert_eq!(result.unwrap(), 1);
}

#[test]
#[cfg(target_os = "linux")]
fn actions_window_clamp_applies_to_socket() {
    let (mut client, _server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let baseline = tcp_window_clamp(&client).expect("read baseline clamp");
    let actions = vec![DesyncAction::SetWindowClamp(2)];
    let result = execute_tcp_actions(
        &mut client,
        &actions,
        64,
        false,
        Duration::from_millis(10),
        None,
        &unavailable,
        false,
        None,
        None,
    );

    assert_eq!(result.unwrap(), 0);
    let applied = tcp_window_clamp(&client).expect("read applied clamp");
    assert!(applied > 0, "clamp should be positive after SetWindowClamp, got {applied}");
    assert!(applied < baseline, "SetWindowClamp should tighten baseline clamp {baseline}, got {applied}",);
}

#[test]
#[cfg(target_os = "linux")]
fn actions_window_clamp_restore_uses_large_effective_clamp() {
    let (mut client, _server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let actions = vec![DesyncAction::SetWindowClamp(2), DesyncAction::RestoreWindowClamp];
    let result = execute_tcp_actions(
        &mut client,
        &actions,
        64,
        false,
        Duration::from_millis(10),
        None,
        &unavailable,
        false,
        None,
        None,
    );

    assert_eq!(result.unwrap(), 0);
    let restored = tcp_window_clamp(&client).expect("read restored clamp");
    assert!(restored > 256, "RestoreWindowClamp should leave an effectively unclamped socket, got {restored}",);
}

#[cfg(target_os = "linux")]
fn tcp_window_clamp(stream: &std::net::TcpStream) -> io::Result<u32> {
    use std::mem;
    use std::os::fd::AsRawFd;

    let mut value: libc::c_int = 0;
    let mut len = mem::size_of_val(&value) as libc::socklen_t;
    // SAFETY: `stream` is a live `TcpStream`, so `as_raw_fd()` yields a valid
    // socket fd for the duration of the call. `TCP_WINDOW_CLAMP` writes a single
    // `c_int`; `&mut value` points to an initialized `c_int` stack local and
    // `&mut len` is initialized to its `size_of`, matching the kernel's expected
    // out-parameter layout for this option. Both pointers outlive the call and
    // are exclusively borrowed here, so no aliasing occurs. The return value is
    // checked below and `errno` is converted via `last_os_error()`.
    let rc = unsafe {
        libc::getsockopt(
            stream.as_raw_fd(),
            libc::IPPROTO_TCP,
            libc::TCP_WINDOW_CLAMP,
            (&mut value as *mut libc::c_int).cast(),
            &mut len,
        )
    };
    if rc == -1 {
        return Err(io::Error::last_os_error());
    }
    Ok(value as u32)
}

// These operations return Unsupported on macOS but succeed on Linux,
// so the "errors on unsupported" assertion only holds off-Linux.
#[test]
#[cfg(not(target_os = "linux"))]
fn actions_await_writable_errors_on_unsupported() {
    let (mut client, _server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let actions = vec![DesyncAction::Write(b"x".to_vec()), DesyncAction::AwaitWritable];
    let err = execute_tcp_actions(
        &mut client,
        &actions,
        64,
        false,
        Duration::from_millis(10),
        Some("split"),
        &unavailable,
        false,
        None,
        None,
    )
    .unwrap_err();
    assert!(matches!(err, OutboundSendError::StrategyExecution { .. }));
}

#[test]
#[cfg(not(target_os = "linux"))]
fn actions_set_md5sig_degrades_gracefully_when_unsupported() {
    // TCP_MD5SIG is a best-effort, privileged fake-packet enhancement. When it
    // is unavailable (non-Linux platform here; EPERM/EACCES on a non-rooted
    // Android device), the action must be SKIPPED so the rest of the desync
    // sequence still runs -- per the non-root baseline -- rather than aborting
    // the connection. Before the graceful-degradation fix this returned a
    // StrategyExecution error and dropped the real payload.
    let (mut client, _server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let actions = vec![DesyncAction::SetMd5Sig { key_len: 16 }, DesyncAction::Write(b"hello".to_vec())];
    let committed = execute_tcp_actions(
        &mut client,
        &actions,
        64,
        false,
        Duration::from_millis(10),
        Some("split"),
        &unavailable,
        false,
        None,
        None,
    )
    .expect("md5sig must degrade gracefully when unsupported, not abort the sequence");
    assert_eq!(committed, 5, "the real payload must still be written after the unsupported md5sig is skipped");
}

#[test]
fn actions_ttl_unavailable_skips_set_restore() {
    let (mut client, _server) = connected_pair();
    let unavailable = AtomicBool::new(true);
    let actions = vec![DesyncAction::SetTtl(1), DesyncAction::Write(b"data".to_vec()), DesyncAction::RestoreDefaultTtl];
    let result = execute_tcp_actions(
        &mut client,
        &actions,
        64,
        false,
        Duration::from_millis(10),
        Some("disorder"),
        &unavailable,
        false,
        None,
        None,
    );
    assert_eq!(result.unwrap(), 4);
}

#[test]
fn actions_safety_net_restores_ttl_on_success() {
    let (mut client, _server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    // SetTtl modifies TTL, then write + no RestoreDefaultTtl -- safety net should restore
    let actions = vec![DesyncAction::SetTtl(42), DesyncAction::Write(b"x".to_vec())];
    let result = execute_tcp_actions(
        &mut client,
        &actions,
        64,
        false,
        Duration::from_millis(10),
        Some("disorder"),
        &unavailable,
        false,
        None,
        None,
    );
    // Should succeed and safety net restores TTL at lines 590-594
    assert_eq!(result.unwrap(), 1);
}

// ---------------------------------------------------------------
// TTL and OOB wrapper tests
// ---------------------------------------------------------------

#[test]
fn set_stream_ttl_loopback() {
    let (client, _server) = connected_pair();
    let result = set_stream_ttl(&client, 42);
    assert!(result.is_ok(), "set_stream_ttl should succeed on loopback: {:?}", result.err());
}

#[test]
fn set_stream_ttl_changes_ipv6_hop_limit() {
    let listener = TcpListener::bind("[::1]:0").expect("IPv6 listener");
    let client = TcpStream::connect(listener.local_addr().expect("IPv6 listener address")).expect("IPv6 client");
    let (_server, _) = listener.accept().expect("accept IPv6 client");
    socket2::SockRef::from(&client).set_unicast_hops_v6(37).expect("initial IPv6 hop limit");

    set_stream_ttl(&client, 42).expect("set IPv6 hop limit");

    assert_eq!(socket2::SockRef::from(&client).unicast_hops_v6().expect("IPv6 hop limit"), 42);
}

#[cfg(any(target_os = "linux", target_os = "android"))]
#[test]
fn set_stream_ttl_keeps_inactive_ipv4_ttl_on_ipv6_socket() {
    let listener = TcpListener::bind("[::1]:0").expect("IPv6 listener");
    let client = TcpStream::connect(listener.local_addr().expect("IPv6 listener address")).expect("IPv6 client");
    let (_server, _) = listener.accept().expect("accept IPv6 client");
    let socket = socket2::SockRef::from(&client);
    socket.set_ttl_v4(37).expect("inactive IPv4 TTL");
    socket.set_unicast_hops_v6(51).expect("initial IPv6 hop limit");

    set_stream_ttl(&client, 42).expect("set active IPv6 hop limit");

    assert_eq!(socket.unicast_hops_v6().expect("active IPv6 hop limit"), 42);
    assert_eq!(socket.ttl_v4().expect("inactive IPv4 TTL"), 37);
}

#[cfg(any(target_os = "linux", target_os = "android"))]
#[test]
fn set_stream_ttl_uses_ipv4_ttl_for_mapped_ipv6_peer() {
    let listener = TcpListener::bind("127.0.0.1:0").expect("IPv4 listener");
    let address = listener.local_addr().expect("IPv4 listener address");
    let client = TcpStream::connect((Ipv4Addr::LOCALHOST.to_ipv6_mapped(), address.port())).expect("mapped client");
    let (_server, _) = listener.accept().expect("accept mapped client");
    let socket = socket2::SockRef::from(&client);
    socket.set_ttl_v4(37).expect("active IPv4 TTL");
    socket.set_unicast_hops_v6(51).expect("inactive IPv6 hop limit");

    assert_eq!(crate::transport_io::get_stream_ttl(&client).expect("active TTL"), 37);
    set_stream_ttl(&client, 42).expect("set active IPv4 TTL");

    assert_eq!(socket.ttl_v4().expect("active IPv4 TTL"), 42);
    assert_eq!(socket.unicast_hops_v6().expect("inactive IPv6 hop limit"), 51);
}

#[test]
fn send_out_of_band_sends_prefix_plus_byte() {
    let (client, _server) = connected_pair();
    let result = send_out_of_band(&client, b"abc", b'!');
    assert!(result.is_ok(), "send_out_of_band should succeed on loopback: {:?}", result.err());
}

#[test]
fn send_oob_action_named_accumulates() {
    let (client, _server) = connected_pair();
    let result = send_oob_action_named(&client, b"ab", b'!', "send_oob", "oob", None, 10);
    assert_eq!(result.unwrap(), 12); // 10 + 2; the urgent byte is not application data
}

// ---------------------------------------------------------------
// execute_tcp_plan validation tests
// ---------------------------------------------------------------

#[test]
fn actions_delay_does_not_affect_bytes_committed() {
    let (mut client, mut server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let actions = vec![
        DesyncAction::Write(b"hello".to_vec()),
        DesyncAction::Delay(1), // 1ms delay
        DesyncAction::Write(b"world".to_vec()),
    ];
    let result = execute_tcp_actions(
        &mut client,
        &actions,
        64,
        false,
        Duration::from_millis(50),
        None,
        &unavailable,
        false,
        None,
        None,
    );
    assert!(result.is_ok());
    assert_eq!(result.unwrap(), 10);

    let mut buf = vec![0u8; 10];
    use std::io::Read;
    server.read_exact(&mut buf).expect("read_exact");
    assert_eq!(&buf, b"helloworld");
}

#[test]
fn oob_partial_prefix_preserves_peer_bytes_and_reports_accepted_progress() {
    use std::io::Read;

    let (client, mut server) = connected_pair();
    socket2::SockRef::from(&client).set_send_buffer_size(1024).expect("small send buffer");
    client.set_nonblocking(true).expect("nonblocking sender");
    server.set_read_timeout(Some(Duration::from_secs(1))).expect("peer timeout");
    let payload = (0..1024 * 1024).map(|index| (index % 251) as u8).collect::<Vec<_>>();
    let err = send_oob_action_named(&client, &payload, b'!', "send_oob", "oob", None, 10)
        .expect_err("a stalled peer must leave an incomplete prefix");
    client.shutdown(std::net::Shutdown::Write).expect("finish partial outbound");
    let mut received = Vec::new();
    server.read_to_end(&mut received).expect("read accepted original bytes");

    assert!(!received.is_empty());
    assert!(received.len() < payload.len());
    assert_eq!(err.bytes_committed(), 10 + received.len());
    assert_eq!(received, payload[..received.len()]);
}

#[test]
fn oob_blocking_prefix_under_send_buffer_pressure_keeps_every_source_byte() {
    use std::io::Read;

    // Linux marks the last byte of each pushed part of one MSG_OOB send urgent, so a split
    // send of the whole prefix removed source bytes from the peer stream.
    let (client, mut server) = connected_pair();
    socket2::SockRef::from(&client).set_send_buffer_size(4096).expect("small send buffer");
    let payload = (0..300_000).map(|index| (index % 251) as u8).collect::<Vec<_>>();
    let reader = std::thread::spawn(move || {
        let mut received = Vec::new();
        let mut chunk = [0u8; 1024];
        loop {
            std::thread::sleep(Duration::from_micros(200));
            match server.read(&mut chunk).expect("read peer stream") {
                0 => return received,
                read => received.extend_from_slice(&chunk[..read]),
            }
        }
    });

    let committed = send_oob_action_named(&client, &payload, b'!', "send_oob", "oob", None, 0).expect("send urgent");
    client.shutdown(std::net::Shutdown::Write).expect("finish outbound");

    assert_eq!(committed, payload.len());
    assert_eq!(reader.join().expect("reader thread"), payload);
}
