use super::*;
use ripdpi_desync::TlsPreludeApplication;

#[test]
fn execute_multidisorder_tcp_plan_rejects_non_contiguous_segment_bounds() {
    let (mut client, _server) = connected_pair();
    let err = execute_multi_disorder_tcp_plan(
        &mut client,
        &RuntimeConfig::default(),
        &multidisorder_chain(),
        &DesyncPlan {
            tampered: b"abcdef".to_vec(),
            steps: vec![
                PlannedStep {
                    kind: TcpChainStepKind::MultiDisorder,
                    start: 0,
                    end: 2,
                    source_send_step_index: Some(0),
                },
                PlannedStep {
                    kind: TcpChainStepKind::MultiDisorder,
                    start: 3,
                    end: 4,
                    source_send_step_index: Some(0),
                },
                PlannedStep {
                    kind: TcpChainStepKind::MultiDisorder,
                    start: 4,
                    end: 6,
                    source_send_step_index: Some(0),
                },
            ],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
            tls_prelude: TlsPreludeApplication::default(),
        },
        Some("multidisorder"),
        false,
        None,
    )
    .expect_err("reject gapped multidisorder plan");

    assert_eq!(err.kind(), io::ErrorKind::InvalidData);
    assert!(err.to_string().contains("invalid multidisorder tcp segment bounds"));
}

#[test]
fn execute_multidisorder_tcp_plan_rejects_partial_payload_coverage() {
    let (mut client, _server) = connected_pair();
    let err = execute_multi_disorder_tcp_plan(
        &mut client,
        &RuntimeConfig::default(),
        &multidisorder_chain(),
        &DesyncPlan {
            tampered: b"abcdef".to_vec(),
            steps: vec![
                PlannedStep {
                    kind: TcpChainStepKind::MultiDisorder,
                    start: 0,
                    end: 2,
                    source_send_step_index: Some(0),
                },
                PlannedStep {
                    kind: TcpChainStepKind::MultiDisorder,
                    start: 2,
                    end: 4,
                    source_send_step_index: Some(0),
                },
                PlannedStep {
                    kind: TcpChainStepKind::MultiDisorder,
                    start: 4,
                    end: 5,
                    source_send_step_index: Some(0),
                },
            ],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
            tls_prelude: TlsPreludeApplication::default(),
        },
        Some("multidisorder"),
        false,
        None,
    )
    .expect_err("reject truncated multidisorder plan");

    assert_eq!(err.kind(), io::ErrorKind::InvalidData);
    assert!(err.to_string().contains("multidisorder tcp plan does not cover the full payload"));
}

#[test]
fn prepare_multidisorder_tcp_plan_accepts_contiguous_full_payload() {
    let mut chain = multidisorder_chain();
    let first_step = chain[0].clone().with_inter_segment_delay_ms(17);
    chain[0] = with_original_flag_overrides(&first_step, TcpFlagOverrides { set: Some(0x12), unset: None });

    let prepared = prepare_multi_disorder_tcp_plan(
        &chain,
        &DesyncPlan {
            tampered: b"abcdef".to_vec(),
            steps: vec![
                PlannedStep {
                    kind: TcpChainStepKind::MultiDisorder,
                    start: 0,
                    end: 2,
                    source_send_step_index: Some(0),
                },
                PlannedStep {
                    kind: TcpChainStepKind::MultiDisorder,
                    start: 2,
                    end: 4,
                    source_send_step_index: Some(0),
                },
                PlannedStep {
                    kind: TcpChainStepKind::MultiDisorder,
                    start: 4,
                    end: 6,
                    source_send_step_index: Some(0),
                },
            ],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
            tls_prelude: TlsPreludeApplication::default(),
        },
        Some("tlsrec_multidisorder"),
    )
    .expect("prepare contiguous multidisorder plan");

    assert_eq!(prepared.strategy_family, "tlsrec_multidisorder");
    assert_eq!(prepared.fallback, None);
    assert_eq!(prepared.inter_segment_delay_ms, 17);
    assert_eq!(prepared.original_flags, step_original_tcp_flags(&chain[0]));
    assert_eq!(
        prepared.segments,
        vec![
            platform::TcpPayloadSegment { start: 0, end: 2 },
            platform::TcpPayloadSegment { start: 2, end: 4 },
            platform::TcpPayloadSegment { start: 4, end: 6 },
        ]
    );
}

#[test]
fn plan_rejects_step_count_mismatch() {
    let (mut client, _server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    // Group has 1 tcp_chain step but plan has 2 steps
    let mut group = test_group();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, test_offset()));

    let err = execute_tcp_plan(
        &mut client,
        &RuntimeConfig::default(),
        &group,
        &DesyncPlan {
            tampered: b"abcdef".to_vec(),
            steps: vec![
                PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: 3, source_send_step_index: Some(0) },
                PlannedStep { kind: TcpChainStepKind::Split, start: 3, end: 6, source_send_step_index: Some(0) },
            ],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
            tls_prelude: TlsPreludeApplication::default(),
        },
        0,
        None,
        TcpPlanStrategyContext { configured_family: Some("split"), tls_prelude_applied: false },
        &unavailable,
    )
    .unwrap_err();
    assert!(err.to_string().contains("tcp plan steps exceed configured send steps"));
}

#[test]
fn plan_rejects_negative_start() {
    let (mut client, _server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let mut group = test_group();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, test_offset()));

    let err = execute_tcp_plan(
        &mut client,
        &RuntimeConfig::default(),
        &group,
        &DesyncPlan {
            tampered: b"abcdef".to_vec(),
            steps: vec![PlannedStep {
                kind: TcpChainStepKind::Split,
                start: -1,
                end: 3,
                source_send_step_index: Some(0),
            }],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
            tls_prelude: TlsPreludeApplication::default(),
        },
        0,
        None,
        TcpPlanStrategyContext { configured_family: Some("split"), tls_prelude_applied: false },
        &unavailable,
    )
    .unwrap_err();
    assert!(err.to_string().contains("negative tcp plan start"));
}

#[test]
fn plan_rejects_negative_end() {
    let (mut client, _server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let mut group = test_group();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, test_offset()));

    let err = execute_tcp_plan(
        &mut client,
        &RuntimeConfig::default(),
        &group,
        &DesyncPlan {
            tampered: b"abcdef".to_vec(),
            steps: vec![PlannedStep {
                kind: TcpChainStepKind::Split,
                start: 0,
                end: -1,
                source_send_step_index: Some(0),
            }],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
            tls_prelude: TlsPreludeApplication::default(),
        },
        0,
        None,
        TcpPlanStrategyContext { configured_family: Some("split"), tls_prelude_applied: false },
        &unavailable,
    )
    .unwrap_err();
    assert!(err.to_string().contains("negative tcp plan end"));
}

#[test]
fn plan_rejects_out_of_order_bounds() {
    let (mut client, _server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let mut group = test_group();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, test_offset()));

    let err = execute_tcp_plan(
        &mut client,
        &RuntimeConfig::default(),
        &group,
        &DesyncPlan {
            tampered: b"abcdef".to_vec(),
            steps: vec![PlannedStep {
                kind: TcpChainStepKind::Split,
                start: 4,
                end: 2,
                source_send_step_index: Some(0),
            }],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
            tls_prelude: TlsPreludeApplication::default(),
        },
        0,
        None,
        TcpPlanStrategyContext { configured_family: Some("split"), tls_prelude_applied: false },
        &unavailable,
    )
    .unwrap_err();
    assert!(err.to_string().contains("invalid tcp desync step bounds"));
}

#[test]
fn plan_rejects_end_beyond_payload() {
    let (mut client, _server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let mut group = test_group();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, test_offset()));

    let err = execute_tcp_plan(
        &mut client,
        &RuntimeConfig::default(),
        &group,
        &DesyncPlan {
            tampered: b"abc".to_vec(),
            steps: vec![PlannedStep {
                kind: TcpChainStepKind::Split,
                start: 0,
                end: 10,
                source_send_step_index: Some(0),
            }],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
            tls_prelude: TlsPreludeApplication::default(),
        },
        0,
        None,
        TcpPlanStrategyContext { configured_family: Some("split"), tls_prelude_applied: false },
        &unavailable,
    )
    .unwrap_err();
    assert!(err.to_string().contains("invalid tcp desync step bounds"));
}

#[test]
fn plan_split_step_writes_chunk() {
    let (mut client, mut server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let mut group = test_group();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, test_offset()));

    let result = execute_tcp_plan(
        &mut client,
        &RuntimeConfig::default(),
        &group,
        &DesyncPlan {
            tampered: b"hello".to_vec(),
            steps: vec![PlannedStep {
                kind: TcpChainStepKind::Split,
                start: 0,
                end: 5,
                source_send_step_index: Some(0),
            }],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
            tls_prelude: TlsPreludeApplication::default(),
        },
        0,
        None,
        TcpPlanStrategyContext { configured_family: Some("split"), tls_prelude_applied: false },
        &unavailable,
    );
    // On macOS, await_writable returns Unsupported after the write succeeds.
    // The write portion (5 bytes) has been committed to the socket.
    // The error is from await_writable, not from the write itself.
    if let Err(err) = &result {
        assert!(matches!(err, OutboundSendError::StrategyExecution { .. }));
    }
    // Regardless of the await error, data should have been written
    server.set_read_timeout(Some(Duration::from_millis(100))).ok();
    let mut buf = vec![0u8; 5];
    use std::io::Read;
    let read_result = server.read(&mut buf);
    assert!(read_result.is_ok(), "data should have been written before await error");
}

#[test]
#[cfg(not(target_os = "linux"))]
fn plan_ipfrag2_fallback_writes_full_payload() {
    let (mut client, mut server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let mut group = test_group();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::IpFrag2, test_offset()));

    let result = execute_tcp_plan(
        &mut client,
        &RuntimeConfig::default(),
        &group,
        &DesyncPlan {
            tampered: b"hello".to_vec(),
            steps: vec![PlannedStep {
                kind: TcpChainStepKind::IpFrag2,
                start: 0,
                end: 2,
                source_send_step_index: Some(0),
            }],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
            tls_prelude: TlsPreludeApplication::default(),
        },
        0,
        None,
        TcpPlanStrategyContext { configured_family: Some("ipfrag2"), tls_prelude_applied: false },
        &unavailable,
    );
    assert_eq!(result.unwrap().bytes_committed, 5);
    server.set_read_timeout(Some(Duration::from_millis(100))).ok();
    let mut buf = vec![0u8; 5];
    use std::io::Read;
    server.read_exact(&mut buf).expect("ipfrag2 fallback should write full payload");
    assert_eq!(&buf, b"hello");
}

#[test]
#[cfg(not(target_os = "linux"))]
fn plan_ipfrag2_fallback_with_original_flags_fails_closed() {
    let (mut client, mut server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let step = with_original_flag_overrides(
        &TcpChainStep::new(TcpChainStepKind::IpFrag2, test_offset()),
        TcpFlagOverrides { set: Some(0x12), unset: None },
    );
    let mut group = test_group();
    group.actions.tcp_chain.push(step);

    let err = execute_tcp_plan(
        &mut client,
        &RuntimeConfig::default(),
        &group,
        &DesyncPlan {
            tampered: b"hello".to_vec(),
            steps: vec![PlannedStep {
                kind: TcpChainStepKind::IpFrag2,
                start: 0,
                end: 2,
                source_send_step_index: Some(0),
            }],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
            tls_prelude: TlsPreludeApplication::default(),
        },
        0,
        None,
        TcpPlanStrategyContext { configured_family: Some("ipfrag2"), tls_prelude_applied: false },
        &unavailable,
    )
    .expect_err("ipfrag2 fallback with original flags should fail closed");
    assert!(matches!(err, OutboundSendError::StrategyExecution { .. }));
    server.set_read_timeout(Some(Duration::from_millis(100))).ok();
    let mut buf = vec![0u8; 5];
    use std::io::Read;
    let read_err = server.read(&mut buf).expect_err("payload should not be written");
    assert!(matches!(read_err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut));
}

#[test]
#[cfg(not(target_os = "linux"))]
fn plan_fakerst_writes_payload_after_fake_reset_attempt() {
    let (mut client, mut server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let mut group = test_group();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::FakeRst, test_offset()));

    let result = execute_tcp_plan(
        &mut client,
        &RuntimeConfig::default(),
        &group,
        &DesyncPlan {
            tampered: b"ping".to_vec(),
            steps: vec![PlannedStep {
                kind: TcpChainStepKind::FakeRst,
                start: 0,
                end: 4,
                source_send_step_index: Some(0),
            }],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
            tls_prelude: TlsPreludeApplication::default(),
        },
        0,
        None,
        TcpPlanStrategyContext { configured_family: Some("fakerst"), tls_prelude_applied: false },
        &unavailable,
    );
    assert_eq!(result.unwrap().bytes_committed, 4);
    server.set_read_timeout(Some(Duration::from_millis(100))).ok();
    let mut buf = vec![0u8; 4];
    use std::io::Read;
    server.read_exact(&mut buf).expect("fakerst branch should still write payload");
    assert_eq!(&buf, b"ping");
}

#[test]
fn plan_hostfake_without_resolved_span_writes_chunk() {
    let (mut client, mut server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let payload = b"GET / HTTP/1.1\r\nHost: sub.example.com\r\n\r\n";
    let markers = http_marker_info(payload).expect("http markers");
    let mut group = test_group();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::HostFake, test_offset()));

    let result = execute_tcp_plan(
        &mut client,
        &RuntimeConfig::default(),
        &group,
        &DesyncPlan {
            tampered: payload.to_vec(),
            steps: vec![PlannedStep {
                kind: TcpChainStepKind::HostFake,
                start: 0,
                end: markers.host_start as i64,
                source_send_step_index: Some(0),
            }],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
            tls_prelude: TlsPreludeApplication::default(),
        },
        23,
        Some(9),
        TcpPlanStrategyContext { configured_family: Some("hostfake"), tls_prelude_applied: false },
        &unavailable,
    );
    if let Err(err) = &result {
        assert!(matches!(err, OutboundSendError::StrategyExecution { .. }));
    }
    server.set_read_timeout(Some(Duration::from_millis(100))).ok();
    let mut buf = vec![0u8; markers.host_start];
    use std::io::Read;
    let read_result = server.read_exact(&mut buf);
    assert!(read_result.is_ok(), "hostfake fallback should write the unresolved span chunk");
    assert_eq!(&buf, &payload[..markers.host_start]);
}

#[test]
#[cfg(not(target_os = "linux"))]
fn plan_hostfake_without_resolved_span_with_original_flags_fails_closed() {
    let (mut client, mut server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let payload = b"GET / HTTP/1.1\r\nHost: sub.example.com\r\n\r\n";
    let markers = http_marker_info(payload).expect("http markers");
    let step = with_original_flag_overrides(
        &TcpChainStep::new(TcpChainStepKind::HostFake, test_offset()),
        TcpFlagOverrides { set: Some(0x12), unset: None },
    );
    let mut group = test_group();
    group.actions.tcp_chain.push(step);

    let err = execute_tcp_plan(
        &mut client,
        &RuntimeConfig::default(),
        &group,
        &DesyncPlan {
            tampered: payload.to_vec(),
            steps: vec![PlannedStep {
                kind: TcpChainStepKind::HostFake,
                start: 0,
                end: markers.host_start as i64,
                source_send_step_index: Some(0),
            }],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
            tls_prelude: TlsPreludeApplication::default(),
        },
        23,
        Some(9),
        TcpPlanStrategyContext { configured_family: Some("hostfake"), tls_prelude_applied: false },
        &unavailable,
    )
    .expect_err("hostfake unresolved-span fallback with original flags should fail closed");
    assert!(matches!(err, OutboundSendError::StrategyExecution { .. }));
    server.set_read_timeout(Some(Duration::from_millis(100))).ok();
    let mut buf = vec![0u8; markers.host_start];
    use std::io::Read;
    let read_err = server.read(&mut buf).expect_err("payload should not be written");
    assert!(matches!(read_err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut));
}

#[test]
#[cfg(not(target_os = "linux"))]
fn plan_fakesplit_terminal_step_with_original_flags_fails_closed() {
    let (mut client, mut server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let step = with_original_flag_overrides(
        &TcpChainStep::new(TcpChainStepKind::FakeSplit, test_offset()),
        TcpFlagOverrides { set: Some(0x12), unset: None },
    );
    let mut group = test_group();
    group.actions.tcp_chain.push(step);

    let err = execute_tcp_plan(
        &mut client,
        &RuntimeConfig::default(),
        &group,
        &DesyncPlan {
            tampered: b"hello".to_vec(),
            steps: vec![PlannedStep {
                kind: TcpChainStepKind::FakeSplit,
                start: 0,
                end: 5,
                source_send_step_index: Some(0),
            }],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
            tls_prelude: TlsPreludeApplication::default(),
        },
        0,
        Some(9),
        TcpPlanStrategyContext { configured_family: Some("fakesplit"), tls_prelude_applied: false },
        &unavailable,
    )
    .expect_err("terminal fakesplit with original flags should fail closed");
    assert!(matches!(err, OutboundSendError::StrategyExecution { .. }));
    server.set_read_timeout(Some(Duration::from_millis(100))).ok();
    let mut buf = vec![0u8; 5];
    use std::io::Read;
    let read_err = server.read(&mut buf).expect_err("payload should not be written");
    assert!(matches!(read_err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut));
}

#[test]
#[cfg(not(target_os = "linux"))]
fn plan_fakeddisorder_terminal_step_with_original_flags_fails_closed() {
    let (mut client, mut server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let step = with_original_flag_overrides(
        &TcpChainStep::new(TcpChainStepKind::FakeDisorder, test_offset()),
        TcpFlagOverrides { set: Some(0x12), unset: None },
    );
    let mut group = test_group();
    group.actions.tcp_chain.push(step);

    let err = execute_tcp_plan(
        &mut client,
        &RuntimeConfig::default(),
        &group,
        &DesyncPlan {
            tampered: b"hello".to_vec(),
            steps: vec![PlannedStep {
                kind: TcpChainStepKind::FakeDisorder,
                start: 0,
                end: 5,
                source_send_step_index: Some(0),
            }],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
            tls_prelude: TlsPreludeApplication::default(),
        },
        0,
        Some(9),
        TcpPlanStrategyContext { configured_family: Some("fakeddisorder"), tls_prelude_applied: false },
        &unavailable,
    )
    .expect_err("terminal fakeddisorder with original flags should fail closed");
    assert!(matches!(err, OutboundSendError::StrategyExecution { .. }));
    server.set_read_timeout(Some(Duration::from_millis(100))).ok();
    let mut buf = vec![0u8; 5];
    use std::io::Read;
    let read_err = server.read(&mut buf).expect_err("payload should not be written");
    assert!(matches!(read_err.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut));
}

#[test]
fn plan_tlsrec_step_errors() {
    let (mut client, _server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let mut group = test_group();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, test_offset()));

    let err = execute_tcp_plan(
        &mut client,
        &RuntimeConfig::default(),
        &group,
        &DesyncPlan {
            tampered: b"abcdef".to_vec(),
            steps: vec![PlannedStep {
                kind: TcpChainStepKind::TlsRec,
                start: 0,
                end: 6,
                source_send_step_index: Some(0),
            }],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
            tls_prelude: TlsPreludeApplication::default(),
        },
        0,
        None,
        TcpPlanStrategyContext { configured_family: Some("tlsrec"), tls_prelude_applied: false },
        &unavailable,
    )
    .unwrap_err();
    assert!(err.to_string().contains("tls prelude step must not appear in tcp send plan"));
}

#[test]
fn multidisorder_rejects_mixed_kinds_in_chain() {
    let (mut client, _server) = connected_pair();
    let chain = vec![
        TcpChainStep::new(TcpChainStepKind::MultiDisorder, OffsetExpr::absolute(2)),
        TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::absolute(4)),
    ];
    let err = execute_multi_disorder_tcp_plan(
        &mut client,
        &RuntimeConfig::default(),
        &chain,
        &DesyncPlan {
            tampered: b"abcdef".to_vec(),
            steps: vec![
                PlannedStep {
                    kind: TcpChainStepKind::MultiDisorder,
                    start: 0,
                    end: 2,
                    source_send_step_index: Some(0),
                },
                PlannedStep {
                    kind: TcpChainStepKind::MultiDisorder,
                    start: 2,
                    end: 4,
                    source_send_step_index: Some(0),
                },
                PlannedStep {
                    kind: TcpChainStepKind::MultiDisorder,
                    start: 4,
                    end: 6,
                    source_send_step_index: Some(0),
                },
            ],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
            tls_prelude: TlsPreludeApplication::default(),
        },
        Some("multidisorder"),
        false,
        None,
    )
    .expect_err("reject mixed chain kinds");
    assert!(err.to_string().contains("invalid multidisorder tcp chain configuration"));
}

#[test]
fn multidisorder_rejects_single_step() {
    let (mut client, _server) = connected_pair();
    let chain = vec![TcpChainStep::new(TcpChainStepKind::MultiDisorder, OffsetExpr::absolute(2))];
    let err = execute_multi_disorder_tcp_plan(
        &mut client,
        &RuntimeConfig::default(),
        &chain,
        &DesyncPlan {
            tampered: b"abcdef".to_vec(),
            steps: vec![
                PlannedStep {
                    kind: TcpChainStepKind::MultiDisorder,
                    start: 0,
                    end: 2,
                    source_send_step_index: Some(0),
                },
                PlannedStep {
                    kind: TcpChainStepKind::MultiDisorder,
                    start: 2,
                    end: 4,
                    source_send_step_index: Some(0),
                },
                PlannedStep {
                    kind: TcpChainStepKind::MultiDisorder,
                    start: 4,
                    end: 6,
                    source_send_step_index: Some(0),
                },
            ],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
            tls_prelude: TlsPreludeApplication::default(),
        },
        Some("multidisorder"),
        false,
        None,
    )
    .expect_err("reject single send step");
    assert!(err.to_string().contains("invalid multidisorder tcp chain configuration"));
}

#[test]
fn multidisorder_rejects_too_few_planned() {
    let (mut client, _server) = connected_pair();
    let err = execute_multi_disorder_tcp_plan(
        &mut client,
        &RuntimeConfig::default(),
        &multidisorder_chain(),
        &DesyncPlan {
            tampered: b"abcdef".to_vec(),
            steps: vec![
                PlannedStep {
                    kind: TcpChainStepKind::MultiDisorder,
                    start: 0,
                    end: 3,
                    source_send_step_index: Some(0),
                },
                PlannedStep {
                    kind: TcpChainStepKind::MultiDisorder,
                    start: 3,
                    end: 6,
                    source_send_step_index: Some(0),
                },
            ],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
            tls_prelude: TlsPreludeApplication::default(),
        },
        Some("multidisorder"),
        false,
        None,
    )
    .expect_err("reject fewer than 3 planned segments");
    assert!(err.to_string().contains("multidisorder requires at least three non-empty planned segments"));
}
