use super::*;
use ripdpi_desync::TlsPreludeApplication;

#[test]
fn last_split_delays_nonempty_tail_in_special_executor() {
    use super::outcome_receipt::ReceiptTestPlatform;
    use std::io::Read;
    use std::time::Instant;
    let payload = b"abcdef";
    let delay = Duration::from_millis(80);
    let mut group = test_group();
    group
        .actions
        .tcp_chain
        .push(TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::absolute(2)).with_inter_segment_delay_ms(80));
    let plan = DesyncPlan {
        http_mutated: None,
        tampered: payload.to_vec(),
        steps: vec![PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: 2, source_send_step_index: Some(0) }],
        proto: ProtoInfo::default(),
        actions: Vec::new(),
        tls_prelude: TlsPreludeApplication::default(),
    };
    let (mut writer, mut peer) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let started = Instant::now();
    let outcome = crate::platform::with_tcp_desync_platform(&ReceiptTestPlatform::default(), || {
        execute_tcp_plan(
            &mut writer,
            &RuntimeConfig::default(),
            &group,
            &plan,
            7,
            None,
            TcpPlanStrategyContext { configured_family: Some("split"), tls_prelude_applied: false },
            &unavailable,
        )
        .expect("split and tail must be sent")
    });
    let elapsed = started.elapsed();
    let mut received = [0u8; 6];
    peer.read_exact(&mut received).expect("read actual peer bytes");
    assert_eq!(&received, payload);
    assert_eq!(outcome.bytes_committed, payload.len());
    assert!(elapsed >= delay, "tail delay was {elapsed:?}");
}
