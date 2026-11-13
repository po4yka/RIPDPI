use super::*;

use super::outcome_receipt::ReceiptTestPlatform;
use std::io::Read;
use std::time::Duration;

#[test]
fn multidisorder_settings_keep_the_first_declared_owner_after_marker_resolution() {
    let payload = b"abcdef";
    for (offsets, inactive_owner) in [([0, 2, 4], false), ([4, 2, 3], false), ([2, 2, 4], false), ([1, 2, 4], true)] {
        let mut group = test_group();
        group.actions.tcp_chain = offsets
            .into_iter()
            .map(|offset| TcpChainStep::new(TcpChainStepKind::MultiDisorder, OffsetExpr::absolute(offset)))
            .collect();
        let owner = group.actions.tcp_chain[0].clone().with_inter_segment_delay_ms(17);
        group.actions.tcp_chain[0] =
            with_original_flag_overrides(&owner, TcpFlagOverrides { set: Some(0x80), unset: Some(0x08) });
        if inactive_owner {
            group.actions.tcp_chain[0] =
                group.actions.tcp_chain[0].clone().with_activation_filter(Some(ripdpi_config::ActivationFilter {
                    round: Some(NumericRange::new(2, 2)),
                    ..Default::default()
                }));
        }
        let context = activation_context_from_progress(
            OutboundProgress { round: 1, payload_size: payload.len(), stream_start: 0, stream_end: payload.len() },
            ActivationTransport::Tcp,
            Some(payload),
            None,
            None,
            None,
            AdaptivePlannerHints::default(),
        );
        let plan = ripdpi_desync::plan_tcp(&group, payload, 1, 64, context).expect("three nonempty regions");
        let prepared = prepare_multi_disorder_tcp_plan(&group.actions.tcp_chain, &plan, None).expect("valid run");

        assert_eq!(prepared.inter_segment_delay_ms, 17);
        assert_eq!(prepared.original_flags, platform::TcpFlagOverrides { set: 0x80, unset: 0x08 });
    }
}

#[test]
fn multidisorder_settings_conflict_fails_before_plain_planning_fallback() {
    let mut group = test_group();
    group.actions.tcp_chain = vec![
        TcpChainStep::new(TcpChainStepKind::MultiDisorder, OffsetExpr::absolute(0)),
        with_original_flag_overrides(
            &TcpChainStep::new(TcpChainStepKind::MultiDisorder, OffsetExpr::absolute(2)),
            TcpFlagOverrides { set: Some(0x80), unset: None },
        ),
    ];
    let payload = b"abcdef";
    let progress =
        OutboundProgress { round: 1, payload_size: payload.len(), stream_start: 0, stream_end: payload.len() };
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        Some(payload),
        None,
        None,
        None,
        AdaptivePlannerHints::default(),
    );
    let (mut client, mut peer) = connected_pair();
    peer.set_read_timeout(Some(Duration::from_secs(1))).expect("read deadline");
    let error = send_prepared_with_group(
        &mut client,
        &ReceiptTestPlatform::default(),
        &RuntimeConfig::default(),
        &group,
        payload,
        progress,
        context,
        None,
        None,
        &default_ttl_unavailable(),
        None,
    )
    .expect_err("conflicting settings must not become a plain write");
    drop(client);
    let mut received = Vec::new();
    peer.read_to_end(&mut received).expect("read peer bytes");

    assert!(received.is_empty());
    assert_eq!(error.kind(), io::ErrorKind::InvalidInput);
    assert!(!error.payload_commit_uncertain());
}
