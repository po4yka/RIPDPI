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
    // write_transport_payload returns bytes.len() (not accumulated), so last write's len is returned
    assert_eq!(result.unwrap(), 5);
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
    let actions = vec![DesyncAction::Write(b"hello".to_vec()), DesyncAction::SetMd5Sig { key_len: 5 }];

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
    .expect_err("unsupported md5sig should fail after the first write");

    match err {
        OutboundSendError::StrategyExecution { execution_receipt, .. } => {
            assert_eq!(execution_receipt.disposition, TcpExecutionDisposition::ExecutionFailed);
            assert_eq!(execution_receipt.effective_family, Some(TcpStrategyFamily::Split));
            assert_eq!(execution_receipt.attempted_actions, 2);
            assert_eq!(execution_receipt.completed_actions, 1);
            assert_eq!(execution_receipt.real_writes_committed, 1);
            assert_eq!(execution_receipt.payload_bytes_committed, 5);
            assert_eq!(execution_receipt.terminal_reason, Some(TcpTerminalReason::StrategyExecution));
        }
        OutboundSendError::Transport(err) => panic!("expected StrategyExecution, got Transport({err})"),
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
    assert_eq!(result.unwrap(), 3); // prefix.len() + 1
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
    assert_eq!(result.unwrap(), 3);
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
fn send_out_of_band_sends_prefix_plus_byte() {
    let (client, _server) = connected_pair();
    let result = send_out_of_band(&client, b"abc", b'!');
    assert!(result.is_ok(), "send_out_of_band should succeed on loopback: {:?}", result.err());
}

#[test]
fn send_oob_action_named_accumulates() {
    let (client, _server) = connected_pair();
    let result = send_oob_action_named(&client, b"ab", b'!', "send_oob", "oob", None, 10);
    assert_eq!(result.unwrap(), 13); // 10 + 2 + 1
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
    assert_eq!(result.unwrap(), 5); // last write's len (write_transport_payload returns per-call bytes)

    let mut buf = vec![0u8; 10];
    use std::io::Read;
    server.read_exact(&mut buf).expect("read_exact");
    assert_eq!(&buf, b"helloworld");
}
