use super::*;
use crate::fake::normalize_fake_tls_size;
use crate::offset::gen_offset;
use crate::tls_prelude::apply_tls_prelude_steps;
use ripdpi_config::{
    ActivationFilter, DesyncGroup, NumericRange, OffsetBase, OffsetExpr, QuicFakeProfile, TcpChainStep,
    TcpChainStepKind, UdpChainStep, UdpChainStepKind, FM_DUPSID, FM_ORIG, FM_PADENCAP, FM_RAND, FM_RNDSNI,
};
use ripdpi_packets::{
    build_realistic_quic_initial, default_fake_quic_compat, http_marker_info, parse_http, parse_quic_initial,
    parse_tls, second_level_domain_span, tls_marker_info, HttpFakeProfile, OracleRng, TlsFakeProfile,
    UdpFakeProfile, DEFAULT_FAKE_HTTP, DEFAULT_FAKE_TLS, IS_HTTP, MH_METHODEOL, MH_UNIXEOL,
    QUIC_V2_VERSION,
};

fn split_expr(pos: i64) -> OffsetExpr {
    OffsetExpr::absolute(pos).with_repeat_skip(1, 0)
}

fn tls_record_lengths(buffer: &[u8]) -> Vec<usize> {
    let mut cursor = 0usize;
    let mut lengths = Vec::new();
    while cursor + 5 <= buffer.len() {
        let len = u16::from_be_bytes([buffer[cursor + 3], buffer[cursor + 4]]) as usize;
        lengths.push(len);
        cursor += 5 + len;
    }
    assert_eq!(cursor, buffer.len());
    lengths
}

fn tcp_context(payload: &[u8]) -> ActivationContext {
    ActivationContext {
        round: 1,
        payload_size: payload.len() as i64,
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1) as i64,
        transport: ActivationTransport::Tcp,
        tcp_segment_hint: None,
        resolved_fake_ttl: None,
        adaptive: AdaptivePlannerHints::default(),
    }
}

fn udp_context(payload: &[u8]) -> ActivationContext {
    ActivationContext {
        round: 1,
        payload_size: payload.len() as i64,
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1) as i64,
        transport: ActivationTransport::Udp,
        tcp_segment_hint: None,
        resolved_fake_ttl: None,
        adaptive: AdaptivePlannerHints::default(),
    }
}

fn tcp_context_with_hint(payload: &[u8], tcp_segment_hint: TcpSegmentHint) -> ActivationContext {
    ActivationContext { tcp_segment_hint: Some(tcp_segment_hint), ..tcp_context(payload) }
}

fn tlsrandrec_step(marker: i64, count: i32, min_size: i32, max_size: i32) -> TcpChainStep {
    TcpChainStep {
        kind: TcpChainStepKind::TlsRandRec,
        offset: OffsetExpr::absolute(marker),
        activation_filter: None,
        midhost_offset: None,
        fake_host_template: None,
        fragment_count: count,
        min_fragment_size: min_size,
        max_fragment_size: max_size,
    }
}

#[test]
fn tcp_segment_hint_budget_uses_fallback_order() {
    assert_eq!(
        TcpSegmentHint { snd_mss: Some(96), advmss: Some(120), pmtu: Some(1500), ip_header_overhead: 40 }
            .adaptive_budget(),
        96,
    );
    assert_eq!(
        TcpSegmentHint { snd_mss: Some(63), advmss: Some(120), pmtu: Some(1500), ip_header_overhead: 40 }
            .adaptive_budget(),
        120,
    );
    assert_eq!(
        TcpSegmentHint { snd_mss: None, advmss: Some(63), pmtu: Some(1500), ip_header_overhead: 40 }
            .adaptive_budget(),
        1460,
    );
    assert_eq!(
        TcpSegmentHint { snd_mss: None, advmss: None, pmtu: None, ip_header_overhead: 40 }.adaptive_budget(),
        1448,
    );
}

#[test]
fn plan_tcp_auto_host_uses_hint_budget_and_semantic_markers() {
    let markers = http_marker_info(DEFAULT_FAKE_HTTP).expect("http markers");
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain =
        vec![TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::adaptive(OffsetBase::AutoHost))];

    let plan = plan_tcp(
        &group,
        DEFAULT_FAKE_HTTP,
        7,
        64,
        tcp_context_with_hint(
            DEFAULT_FAKE_HTTP,
            TcpSegmentHint {
                snd_mss: None,
                advmss: None,
                pmtu: Some(markers.host_end as i64 + 40),
                ip_header_overhead: 40,
            },
        ),
    )
    .expect("adaptive host plan");

    assert_eq!(
        plan.steps,
        vec![PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: markers.host_end as i64 }]
    );
}

#[test]
fn plan_tcp_auto_marker_is_cursor_aware_across_chain_steps() {
    let markers = http_marker_info(DEFAULT_FAKE_HTTP).expect("http markers");
    let host = &DEFAULT_FAKE_HTTP[markers.host_start..markers.host_end];
    let (sld_start, sld_end) = second_level_domain_span(host).expect("sld span");
    let midsld = (markers.host_start + sld_start + ((sld_end - sld_start) / 2)) as i64;
    let remaining = DEFAULT_FAKE_HTTP.len().saturating_sub(markers.host_start);
    let target_end = markers.host_start as i64 + ((remaining.max(1) / 2) as i64);
    let expected_second_end = if markers.host_end as i64 <= target_end { markers.host_end as i64 } else { midsld };

    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain = vec![
        TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::marker(OffsetBase::Host, 0)),
        TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::adaptive(OffsetBase::AutoHost)),
    ];

    let plan =
        plan_tcp(&group, DEFAULT_FAKE_HTTP, 7, 64, tcp_context(DEFAULT_FAKE_HTTP)).expect("cursor-aware plan");

    assert_eq!(
        plan.steps[0],
        PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: markers.host_start as i64 }
    );
    assert_eq!(
        plan.steps[1],
        PlannedStep { kind: TcpChainStepKind::Split, start: markers.host_start as i64, end: expected_second_end },
    );
}

#[test]
fn plan_tcp_auto_marker_falls_back_to_payload_target_without_semantics() {
    let payload = b"plain payload without semantic markers";
    let expected = (payload.len() / 2).max(1) as i64;
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain =
        vec![TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::adaptive(OffsetBase::AutoBalanced))];

    let plan = plan_tcp(&group, payload, 7, 64, tcp_context(payload)).expect("fallback plan");

    assert_eq!(plan.steps, vec![PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: expected }]);
}

#[test]
fn plan_tcp_tlsrec_supports_adaptive_marker_resolution() {
    let markers = tls_marker_info(DEFAULT_FAKE_TLS).expect("tls markers");
    let mut auto_group = DesyncGroup::new(0);
    auto_group.actions.tcp_chain =
        vec![TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::adaptive(OffsetBase::AutoSniExt))];
    let mut explicit_group = DesyncGroup::new(0);
    explicit_group.actions.tcp_chain =
        vec![TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::marker(OffsetBase::SniExt, 0))];

    let auto_plan = plan_tcp(
        &auto_group,
        DEFAULT_FAKE_TLS,
        7,
        64,
        tcp_context_with_hint(
            DEFAULT_FAKE_TLS,
            TcpSegmentHint {
                snd_mss: None,
                advmss: None,
                pmtu: Some(markers.sni_ext_start as i64 + 41),
                ip_header_overhead: 40,
            },
        ),
    )
    .expect("auto tlsrec plan");
    let explicit_plan = plan_tcp(&explicit_group, DEFAULT_FAKE_TLS, 7, 64, tcp_context(DEFAULT_FAKE_TLS))
        .expect("explicit tlsrec plan");

    assert_eq!(auto_plan.tampered, explicit_plan.tampered);
}

#[test]
fn plan_tcp_tlsrandrec_supports_adaptive_marker_resolution() {
    let markers = tls_marker_info(DEFAULT_FAKE_TLS).expect("tls markers");
    let mut auto_group = DesyncGroup::new(0);
    auto_group.actions.tcp_chain = vec![TcpChainStep {
        kind: TcpChainStepKind::TlsRandRec,
        offset: OffsetExpr::adaptive(OffsetBase::AutoSniExt),
        activation_filter: None,
        midhost_offset: None,
        fake_host_template: None,
        fragment_count: 4,
        min_fragment_size: 16,
        max_fragment_size: 32,
    }];
    let mut explicit_group = DesyncGroup::new(0);
    explicit_group.actions.tcp_chain = vec![TcpChainStep {
        kind: TcpChainStepKind::TlsRandRec,
        offset: OffsetExpr::marker(OffsetBase::SniExt, 0),
        activation_filter: None,
        midhost_offset: None,
        fake_host_template: None,
        fragment_count: 4,
        min_fragment_size: 16,
        max_fragment_size: 32,
    }];

    let auto_plan = plan_tcp(
        &auto_group,
        DEFAULT_FAKE_TLS,
        7,
        64,
        tcp_context_with_hint(
            DEFAULT_FAKE_TLS,
            TcpSegmentHint {
                snd_mss: None,
                advmss: None,
                pmtu: Some(markers.sni_ext_start as i64 + 41),
                ip_header_overhead: 40,
            },
        ),
    )
    .expect("auto tlsrandrec plan");
    let explicit_plan = plan_tcp(&explicit_group, DEFAULT_FAKE_TLS, 7, 64, tcp_context(DEFAULT_FAKE_TLS))
        .expect("explicit tlsrandrec plan");

    assert_eq!(auto_plan.tampered, explicit_plan.tampered);
}

#[test]
fn plan_tcp_prefers_adaptive_split_hint_when_candidate_is_valid() {
    let markers = http_marker_info(DEFAULT_FAKE_HTTP).expect("http markers");
    let host = &DEFAULT_FAKE_HTTP[markers.host_start..markers.host_end];
    let (sld_start, sld_end) = second_level_domain_span(host).expect("sld span");
    let midsld = (markers.host_start + sld_start + ((sld_end - sld_start) / 2)) as i64;
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain =
        vec![TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::adaptive(OffsetBase::AutoHost))];
    let mut hinted = tcp_context_with_hint(
        DEFAULT_FAKE_HTTP,
        TcpSegmentHint { snd_mss: None, advmss: None, pmtu: Some(midsld + 40), ip_header_overhead: 40 },
    );
    hinted.adaptive.split_offset_base = Some(OffsetBase::MidSld);

    let plan = plan_tcp(&group, DEFAULT_FAKE_HTTP, 7, 64, hinted).expect("hinted split plan");

    assert_eq!(plan.steps, vec![PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: midsld }]);
}

#[test]
fn tlsrandrec_profiles_adjust_fragment_lengths() {
    let payload_len = DEFAULT_FAKE_TLS.len() - 5;
    let marker = (payload_len - 96) as i64;
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain = vec![tlsrandrec_step(marker, 3, 24, 40)];

    let balanced = apply_tamper(&group, DEFAULT_FAKE_TLS, 7).expect("balanced");
    let mut tight_context = tcp_context(DEFAULT_FAKE_TLS);
    tight_context.adaptive.tlsrandrec_profile = Some(AdaptiveTlsRandRecProfile::Tight);
    let tight =
        apply_tls_prelude_steps(&group, &group.actions.tcp_chain, DEFAULT_FAKE_TLS, 7, tight_context)
            .expect("tight");
    let mut wide_context = tcp_context(DEFAULT_FAKE_TLS);
    wide_context.adaptive.tlsrandrec_profile = Some(AdaptiveTlsRandRecProfile::Wide);
    let wide = apply_tls_prelude_steps(&group, &group.actions.tcp_chain, DEFAULT_FAKE_TLS, 7, wide_context)
        .expect("wide");

    let balanced_lengths = tls_record_lengths(&balanced.bytes);
    let tight_lengths = tls_record_lengths(&tight.bytes);
    let wide_lengths = tls_record_lengths(&wide.bytes);

    assert_eq!(balanced_lengths.len(), 4);
    assert_eq!(tight_lengths.len(), 4);
    assert_eq!(wide_lengths.len(), 4);
    assert!(tight_lengths.iter().max() <= balanced_lengths.iter().max());
    assert!(wide_lengths.iter().max() >= balanced_lengths.iter().max());
}

#[test]
fn plan_tcp_split_emits_chunk_and_tail_actions() {
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, split_expr(5)));
    let payload = b"hello world";

    let plan = plan_tcp(&group, payload, 7, 64, tcp_context(payload)).expect("plan split tcp");

    assert_eq!(plan.tampered, payload);
    assert_eq!(plan.steps, vec![PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: 5 }]);
    assert_eq!(
        plan.actions,
        vec![
            DesyncAction::Write(b"hello".to_vec()),
            DesyncAction::AwaitWritable,
            DesyncAction::Write(b" world".to_vec()),
        ]
    );
}

#[test]
fn plan_tcp_fake_uses_fake_chunk_then_original_tail() {
    let mut group = DesyncGroup::new(0);
    group.actions.ttl = Some(9);
    group.actions.fake_data = Some(b"FAKEPAYLOAD".to_vec());
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Fake, split_expr(4)));
    let payload = b"hello world";

    let plan = plan_tcp(&group, payload, 3, 32, tcp_context(payload)).expect("plan fake tcp");

    assert_eq!(
        plan.actions,
        vec![
            DesyncAction::SetTtl(9),
            DesyncAction::Write(b"FAKE".to_vec()),
            DesyncAction::RestoreDefaultTtl,
            DesyncAction::SetTtl(32),
            DesyncAction::Write(b"o world".to_vec()),
        ]
    );
}

#[test]
fn plan_tcp_fake_prefers_resolved_fake_ttl_over_group_ttl() {
    let mut group = DesyncGroup::new(0);
    group.actions.ttl = Some(9);
    group.actions.fake_data = Some(b"FAKEPAYLOAD".to_vec());
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Fake, split_expr(4)));
    let payload = b"hello world";
    let mut context = tcp_context(payload);
    context.resolved_fake_ttl = Some(5);

    let plan = plan_tcp(&group, payload, 3, 32, context).expect("plan fake tcp");

    assert_eq!(
        plan.actions,
        vec![
            DesyncAction::SetTtl(5),
            DesyncAction::Write(b"FAKE".to_vec()),
            DesyncAction::RestoreDefaultTtl,
            DesyncAction::SetTtl(32),
            DesyncAction::Write(b"o world".to_vec()),
        ]
    );
}

#[test]
fn build_fake_region_bytes_repeats_fake_stream_from_offset() {
    let fake = FakePacketPlan { bytes: b"ABCDEFG".to_vec(), fake_offset: 2, proto: ProtoInfo::default() };

    assert_eq!(build_fake_region_bytes(&fake, 0, 6), b"CDEFGC".to_vec());
    assert_eq!(build_fake_region_bytes(&fake, 3, 5), b"FGCDE".to_vec());
    assert_eq!(build_fake_region_bytes(&fake, 0, 0), Vec::<u8>::new());
}

#[test]
fn build_fake_region_bytes_falls_back_to_zeroes_when_fake_stream_is_empty() {
    let fake = FakePacketPlan { bytes: b"ABC".to_vec(), fake_offset: 3, proto: ProtoInfo::default() };

    assert_eq!(build_fake_region_bytes(&fake, 7, 4), vec![0, 0, 0, 0]);
}

#[test]
fn plan_tcp_fakedsplit_keeps_fake_step_when_split_is_valid() {
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::FakeSplit, split_expr(4))];
    let payload = b"abcdefgh";

    let plan = plan_tcp(&group, payload, 5, 32, tcp_context(payload)).expect("plan fakedsplit tcp");

    assert_eq!(plan.steps, vec![PlannedStep { kind: TcpChainStepKind::FakeSplit, start: 0, end: 4 }]);
    assert_eq!(
        plan.actions,
        vec![
            DesyncAction::Write(b"abcd".to_vec()),
            DesyncAction::AwaitWritable,
            DesyncAction::Write(b"efgh".to_vec()),
        ]
    );
}

#[test]
fn plan_tcp_fakedsplit_degrades_to_split_when_second_region_is_empty() {
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::FakeSplit, split_expr(8))];
    let payload = b"abcdefgh";

    let plan = plan_tcp(&group, payload, 5, 32, tcp_context(payload)).expect("plan fakedsplit tcp");

    assert_eq!(plan.steps, vec![PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: 8 }]);
}

#[test]
fn plan_tcp_fakeddisorder_keeps_fake_step_when_split_is_valid() {
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::FakeDisorder, split_expr(3))];
    let payload = b"abcdef";

    let plan = plan_tcp(&group, payload, 5, 32, tcp_context(payload)).expect("plan fakeddisorder tcp");

    assert_eq!(plan.steps, vec![PlannedStep { kind: TcpChainStepKind::FakeDisorder, start: 0, end: 3 }]);
    assert_eq!(
        plan.actions,
        vec![
            DesyncAction::SetTtl(1),
            DesyncAction::Write(b"abc".to_vec()),
            DesyncAction::AwaitWritable,
            DesyncAction::RestoreDefaultTtl,
            DesyncAction::Write(b"def".to_vec()),
        ]
    );
}

#[test]
fn plan_tcp_fakeddisorder_degrades_to_disorder_when_second_region_is_empty() {
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::FakeDisorder, split_expr(6))];
    let payload = b"abcdef";

    let plan = plan_tcp(&group, payload, 5, 32, tcp_context(payload)).expect("plan fakeddisorder tcp");

    assert_eq!(plan.steps, vec![PlannedStep { kind: TcpChainStepKind::Disorder, start: 0, end: 6 }]);
}

#[test]
fn plan_tcp_fake_approx_steps_support_adaptive_markers() {
    let markers = http_marker_info(DEFAULT_FAKE_HTTP).expect("http markers");
    let mut split_group = DesyncGroup::new(0);
    split_group.actions.tcp_chain =
        vec![TcpChainStep::new(TcpChainStepKind::FakeSplit, OffsetExpr::adaptive(OffsetBase::AutoHost))];
    let mut disorder_group = DesyncGroup::new(0);
    disorder_group.actions.tcp_chain =
        vec![TcpChainStep::new(TcpChainStepKind::FakeDisorder, OffsetExpr::adaptive(OffsetBase::AutoHost))];

    let split_plan = plan_tcp(
        &split_group,
        DEFAULT_FAKE_HTTP,
        7,
        64,
        tcp_context_with_hint(
            DEFAULT_FAKE_HTTP,
            TcpSegmentHint {
                snd_mss: None,
                advmss: None,
                pmtu: Some(markers.host_start as i64 + 40),
                ip_header_overhead: 40,
            },
        ),
    )
    .expect("adaptive fakedsplit");
    let disorder_plan = plan_tcp(
        &disorder_group,
        DEFAULT_FAKE_HTTP,
        7,
        64,
        tcp_context_with_hint(
            DEFAULT_FAKE_HTTP,
            TcpSegmentHint {
                snd_mss: None,
                advmss: None,
                pmtu: Some(markers.host_start as i64 + 40),
                ip_header_overhead: 40,
            },
        ),
    )
    .expect("adaptive fakeddisorder");

    assert_eq!(
        split_plan.steps[0],
        PlannedStep { kind: TcpChainStepKind::FakeSplit, start: 0, end: markers.host_start as i64 }
    );
    assert_eq!(
        disorder_plan.steps[0],
        PlannedStep { kind: TcpChainStepKind::FakeDisorder, start: 0, end: markers.host_start as i64 },
    );
}

#[test]
fn plan_tcp_disorder_emits_ttl_write_await_restore() {
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Disorder, split_expr(3)));
    let payload = b"abcdef";

    let plan = plan_tcp(&group, payload, 1, 0, tcp_context(payload)).expect("plan disorder tcp");

    assert_eq!(
        plan.actions,
        vec![
            DesyncAction::SetTtl(1),
            DesyncAction::Write(b"abc".to_vec()),
            DesyncAction::AwaitWritable,
            DesyncAction::RestoreDefaultTtl,
            DesyncAction::Write(b"def".to_vec()),
        ]
    );
}

#[test]
fn plan_tcp_oob_emits_write_urgent() {
    let mut group = DesyncGroup::new(0);
    group.actions.oob_data = Some(b'Z');
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Oob, split_expr(4)));
    let payload = b"abcdefgh";

    let plan = plan_tcp(&group, payload, 2, 0, tcp_context(payload)).expect("plan oob tcp");

    assert_eq!(
        plan.actions,
        vec![
            DesyncAction::WriteUrgent { prefix: b"abcd".to_vec(), urgent_byte: b'Z' },
            DesyncAction::AwaitWritable,
            DesyncAction::Write(b"efgh".to_vec()),
        ]
    );
}

#[test]
fn plan_tcp_disoob_combines_ttl_and_urgent() {
    let mut group = DesyncGroup::new(0);
    group.actions.oob_data = Some(b'X');
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Disoob, split_expr(2)));
    let payload = b"abcdef";

    let plan = plan_tcp(&group, payload, 0, 0, tcp_context(payload)).expect("plan disoob tcp");

    assert_eq!(
        plan.actions,
        vec![
            DesyncAction::SetTtl(1),
            DesyncAction::WriteUrgent { prefix: b"ab".to_vec(), urgent_byte: b'X' },
            DesyncAction::AwaitWritable,
            DesyncAction::RestoreDefaultTtl,
            DesyncAction::Write(b"cdef".to_vec()),
        ]
    );
}

#[test]
fn plan_tcp_chain_preserves_tlsrec_prelude_and_send_step_order() {
    let mut group = DesyncGroup::new(0);
    group.actions.fake_data = Some(b"FAKEPAYLOAD".to_vec());
    group.actions.tlsminor = Some(1);
    group.actions.tcp_chain = vec![
        TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::marker(OffsetBase::ExtLen, 0)),
        TcpChainStep::new(TcpChainStepKind::Fake, split_expr(4)),
        TcpChainStep::new(TcpChainStepKind::Split, split_expr(7)),
    ];

    let plan = plan_tcp(&group, DEFAULT_FAKE_TLS, 9, 32, tcp_context(DEFAULT_FAKE_TLS)).expect("plan chained tcp");

    assert_eq!(
        plan.steps,
        vec![
            PlannedStep { kind: TcpChainStepKind::Fake, start: 0, end: 4 },
            PlannedStep { kind: TcpChainStepKind::Split, start: 4, end: 7 },
        ]
    );
    assert_eq!(plan.tampered[2], 1);
    assert!(plan.tampered.len() > DEFAULT_FAKE_TLS.len());
}

#[test]
fn plan_tcp_group_activation_filter_can_disable_desync() {
    let mut group = DesyncGroup::new(0);
    group.set_activation_filter(ActivationFilter {
        round: Some(NumericRange::new(2, 4)),
        payload_size: None,
        stream_bytes: None,
    });
    group.actions.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::Split, split_expr(5))];
    let payload = b"hello world";

    let plan = plan_tcp(&group, payload, 7, 64, tcp_context(payload)).expect("plan tcp");

    assert!(plan.steps.is_empty());
    assert_eq!(plan.tampered, payload);
    assert_eq!(plan.actions, vec![DesyncAction::Write(payload.to_vec())]);
}

#[test]
fn plan_tcp_applies_extended_http_parser_evasions_only_to_http_requests() {
    let mut group = DesyncGroup::new(0);
    group.actions.mod_http = MH_UNIXEOL | MH_METHODEOL;
    group.actions.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::Split, split_expr(5))];
    let payload = b"GET / HTTP/1.1\r\nHost: example.com\r\nUser-Agent: agent\r\n\r\n";

    let plan = plan_tcp(&group, payload, 7, 64, tcp_context(payload)).expect("plan tcp");
    let output = std::str::from_utf8(&plan.tampered).expect("tampered http");

    assert!(output.starts_with("\r\nGET / HTTP/1.1\n"));
    assert!(output.contains("\nUser-Agent: agent  \n\n"));
}

#[test]
fn plan_tcp_http_parser_evasion_fallback_keeps_payload_when_mutation_cannot_apply() {
    let mut group = DesyncGroup::new(0);
    group.actions.mod_http = MH_UNIXEOL | MH_METHODEOL;
    group.actions.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::Split, split_expr(5))];
    let payload = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";

    let plan = plan_tcp(&group, payload, 7, 64, tcp_context(payload)).expect("plan tcp");

    assert_eq!(plan.tampered, payload);
}

#[test]
fn plan_tcp_ignores_http_parser_evasions_for_non_http_payloads() {
    let mut group = DesyncGroup::new(0);
    group.actions.mod_http = MH_UNIXEOL | MH_METHODEOL;
    group.actions.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::Split, split_expr(5))];
    let payload = b"\x16\x03\x01\x00\x10not-http-payload";

    let plan = plan_tcp(&group, payload, 7, 64, tcp_context(payload)).expect("plan tcp");

    assert_eq!(plan.tampered, payload);
    assert_eq!(
        plan.actions,
        vec![
            DesyncAction::Write(payload[..5].to_vec()),
            DesyncAction::AwaitWritable,
            DesyncAction::Write(payload[5..].to_vec()),
        ]
    );
}

#[test]
fn plan_tcp_step_activation_filter_skips_tls_prelude_only() {
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain = vec![
        TcpChainStep {
            kind: TcpChainStepKind::TlsRec,
            offset: OffsetExpr::marker(OffsetBase::ExtLen, 0),
            activation_filter: Some(ActivationFilter {
                round: Some(NumericRange::new(2, 3)),
                payload_size: None,
                stream_bytes: None,
            }),
            midhost_offset: None,
            fake_host_template: None,
            fragment_count: 0,
            min_fragment_size: 0,
            max_fragment_size: 0,
        },
        TcpChainStep::new(TcpChainStepKind::Split, split_expr(4)),
    ];

    let plan = plan_tcp(&group, DEFAULT_FAKE_TLS, 7, 64, tcp_context(DEFAULT_FAKE_TLS)).expect("plan tcp");

    assert_eq!(plan.steps, vec![PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: 4 }]);
    assert_eq!(plan.tampered, DEFAULT_FAKE_TLS);
}

#[test]
fn plan_tcp_rejects_tlsrec_after_send_step() {
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain = vec![
        TcpChainStep::new(TcpChainStepKind::Split, split_expr(2)),
        TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::marker(OffsetBase::ExtLen, 0)),
    ];

    assert!(plan_tcp(&group, DEFAULT_FAKE_TLS, 7, 64, tcp_context(DEFAULT_FAKE_TLS)).is_err());
}

#[test]
fn apply_tamper_tlsrandrec_is_noop_for_non_tls_payloads() {
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain = vec![tlsrandrec_step(0, 4, 16, 32)];

    let tampered = apply_tamper(&group, b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n", 7).expect("tamper http");

    assert_eq!(tampered.bytes, b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n");
}

#[test]
fn apply_tamper_tlsrandrec_is_noop_when_marker_cannot_be_resolved() {
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain = vec![TcpChainStep {
        kind: TcpChainStepKind::TlsRandRec,
        offset: OffsetExpr::marker(OffsetBase::Method, 0),
        activation_filter: None,
        midhost_offset: None,
        fake_host_template: None,
        fragment_count: 4,
        min_fragment_size: 16,
        max_fragment_size: 32,
    }];

    let tampered = apply_tamper(&group, DEFAULT_FAKE_TLS, 7).expect("tamper tls");

    assert_eq!(tampered.bytes, DEFAULT_FAKE_TLS);
}

#[test]
fn apply_tamper_tlsrandrec_is_noop_when_layout_is_impossible() {
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain = vec![tlsrandrec_step(0, 16, 4096, 4096)];

    let tampered = apply_tamper(&group, DEFAULT_FAKE_TLS, 7).expect("tamper impossible");

    assert_eq!(tampered.bytes, DEFAULT_FAKE_TLS);
}

#[test]
fn apply_tamper_tlsrandrec_rewrites_clienthello_into_expected_record_lengths() {
    let payload_len = DEFAULT_FAKE_TLS.len() - 5;
    let marker = (payload_len - 96) as i64;
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain = vec![tlsrandrec_step(marker, 3, 32, 32)];

    let tampered = apply_tamper(&group, DEFAULT_FAKE_TLS, 7).expect("tamper tlsrandrec");

    assert_eq!(tls_record_lengths(&tampered.bytes), vec![payload_len - 96, 32, 32, 32]);
    assert_eq!(tampered.bytes[0], DEFAULT_FAKE_TLS[0]);
    assert_eq!(tampered.bytes[1], DEFAULT_FAKE_TLS[1]);
    assert_eq!(tampered.bytes[2], DEFAULT_FAKE_TLS[2]);
}

#[test]
fn apply_tamper_tlsrandrec_seed_changes_randomized_layout() {
    let payload_len = DEFAULT_FAKE_TLS.len() - 5;
    let marker = (payload_len - 96) as i64;
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain = vec![tlsrandrec_step(marker, 3, 24, 40)];

    let seed_a = apply_tamper(&group, DEFAULT_FAKE_TLS, 7).expect("seed a");
    let seed_b = apply_tamper(&group, DEFAULT_FAKE_TLS, 8).expect("seed b");

    assert_ne!(seed_a.bytes, seed_b.bytes);
}

#[test]
fn plan_tcp_supports_mixed_tls_preludes_before_send_steps() {
    let payload_len = DEFAULT_FAKE_TLS.len() - 5;
    let marker = (payload_len - 96) as i64;
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain = vec![
        TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::marker(OffsetBase::ExtLen, 0)),
        tlsrandrec_step(marker, 3, 32, 32),
        TcpChainStep::new(TcpChainStepKind::Split, split_expr(4)),
    ];

    let plan =
        plan_tcp(&group, DEFAULT_FAKE_TLS, 7, 64, tcp_context(DEFAULT_FAKE_TLS)).expect("mixed tls preludes");

    assert_eq!(plan.steps, vec![PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: 4 }]);
    assert_eq!(tls_record_lengths(&plan.tampered), vec![payload_len - 96, 32, 32, 32]);
}

#[test]
fn normalize_fake_tls_size_all_branches() {
    // negative -> saturating_sub
    assert_eq!(normalize_fake_tls_size(-5, 100), 95);
    assert_eq!(normalize_fake_tls_size(-200, 100), 0);
    // zero -> input_len
    assert_eq!(normalize_fake_tls_size(0, 100), 100);
    // positive -> explicit target size
    assert_eq!(normalize_fake_tls_size(200, 100), 200);
    // positive in range -> value
    assert_eq!(normalize_fake_tls_size(50, 100), 50);
}

#[test]
fn build_fake_packet_applies_rndsni_and_dupsid_in_order() {
    let mut group = DesyncGroup::new(0);
    group.actions.fake_mod = FM_ORIG | FM_RAND | FM_RNDSNI | FM_DUPSID;
    let payload = DEFAULT_FAKE_TLS;
    let expected_sid = payload[44..44 + payload[43] as usize].to_vec();
    let original_host = ripdpi_packets::parse_tls(payload).expect("original tls host").to_vec();

    let fake = build_fake_packet(&group, payload, 19).expect("fake packet");
    let fake_host = ripdpi_packets::parse_tls(&fake.bytes).expect("fake tls host").to_vec();

    assert_ne!(fake_host, original_host);
    assert_eq!(&fake.bytes[44..44 + fake.bytes[43] as usize], expected_sid.as_slice());
}

#[test]
fn build_fake_packet_applies_fake_tls_size_to_default_and_orig_bases() {
    let mut default_group = DesyncGroup::new(0);
    default_group.actions.fake_mod = FM_RNDSNI;
    default_group.actions.fake_tls_size = (DEFAULT_FAKE_TLS.len() + 12) as i32;

    let default_fake = build_fake_packet(&default_group, DEFAULT_FAKE_TLS, 3).expect("default fake tls");
    assert_eq!(default_fake.bytes.len(), DEFAULT_FAKE_TLS.len() + 12);

    let mut orig_group = DesyncGroup::new(0);
    orig_group.actions.fake_mod = FM_ORIG | FM_RNDSNI;
    orig_group.actions.fake_tls_size = (DEFAULT_FAKE_TLS.len() + 8) as i32;

    let orig_fake = build_fake_packet(&orig_group, DEFAULT_FAKE_TLS, 5).expect("orig fake tls");
    assert_eq!(orig_fake.bytes.len(), DEFAULT_FAKE_TLS.len() + 8);
}

#[test]
fn build_fake_packet_ignores_tls_mods_for_http_payloads() {
    let mut group = DesyncGroup::new(0);
    group.actions.fake_mod = FM_RAND | FM_RNDSNI | FM_DUPSID | FM_PADENCAP;

    let fake = build_fake_packet(&group, b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n", 11).expect("http fake");

    assert_eq!(fake.bytes, DEFAULT_FAKE_HTTP);
    assert_eq!(fake.proto.kind, IS_HTTP);
}

#[test]
fn build_fake_packet_padencap_keeps_valid_tls() {
    let mut group = DesyncGroup::new(0);
    group.actions.fake_mod = FM_PADENCAP;

    let fake = build_fake_packet(&group, DEFAULT_FAKE_TLS, 7).expect("padencap fake");

    assert!(ripdpi_packets::parse_tls(&fake.bytes).is_some());
    assert_eq!(fake.bytes.len(), DEFAULT_FAKE_TLS.len());
    assert!(fake.fake_offset <= fake.bytes.len());
}

#[test]
fn plan_udp_no_fake_only_drop_sack() {
    let mut group = DesyncGroup::new(0);
    group.actions.drop_sack = true;

    let actions = plan_udp(&group, b"data", 0, udp_context(b"data"));

    assert_eq!(
        actions,
        vec![DesyncAction::AttachDropSack, DesyncAction::Write(b"data".to_vec()), DesyncAction::DetachDropSack,]
    );
}

#[test]
fn gen_offset_end_mid_rand_flags() {
    let mut info = ProtoInfo::default();
    let mut rng = OracleRng::seeded(42);
    let buf = b"0123456789";

    let expr_end = OffsetExpr::marker(OffsetBase::PayloadEnd, -3);
    assert_eq!(gen_offset(expr_end, buf, buf.len(), 0, &mut info, &mut rng), Some(7));

    let expr_mid = OffsetExpr::marker(OffsetBase::PayloadMid, 0);
    assert_eq!(gen_offset(expr_mid, buf, buf.len(), 0, &mut info, &mut rng), Some(5));

    let expr_rand = OffsetExpr::marker(OffsetBase::PayloadRand, 0);
    let result = gen_offset(expr_rand, buf, buf.len(), 0, &mut info, &mut rng).expect("payload rand");
    assert!(result >= 0 && result <= buf.len() as i64);
}

#[test]
fn gen_offset_resolves_named_markers() {
    let mut info = ProtoInfo::default();
    let mut rng = OracleRng::seeded(7);
    let http = b"\r\nGET / HTTP/1.1\r\nHost: sub.example.com\r\n\r\n";
    let tls = DEFAULT_FAKE_TLS;
    let expected_http_mid = 24 + 4 + ((11 - 4) / 2);
    let tls_markers = tls_marker_info(tls).expect("tls markers");

    assert_eq!(
        gen_offset(OffsetExpr::marker(OffsetBase::Method, 0), http, http.len(), 0, &mut info, &mut rng),
        Some(2)
    );
    assert_eq!(
        gen_offset(OffsetExpr::marker(OffsetBase::MidSld, 0), http, http.len(), 0, &mut info, &mut rng),
        Some(expected_http_mid as i64)
    );

    let mut tls_info = ProtoInfo::default();
    let mut tls_rng = OracleRng::seeded(7);
    assert_eq!(
        gen_offset(OffsetExpr::marker(OffsetBase::SniExt, 0), tls, tls.len(), 0, &mut tls_info, &mut tls_rng),
        Some(tls_markers.sni_ext_start as i64)
    );
    assert_eq!(
        gen_offset(OffsetExpr::marker(OffsetBase::ExtLen, 0), tls, tls.len(), 0, &mut tls_info, &mut tls_rng),
        Some(tls_markers.ext_len_start as i64)
    );
}

#[test]
fn build_hostfake_bytes_preserves_length_and_template_suffix() {
    let fake = build_hostfake_bytes(b"video.example.com", Some("googlevideo.com"), 17);

    assert_eq!(fake.len(), b"video.example.com".len());
    assert!(fake
        .iter()
        .all(|byte| byte.is_ascii_lowercase() || byte.is_ascii_digit() || matches!(byte, b'.' | b'-')));
    assert!(std::str::from_utf8(&fake).unwrap().ends_with("video.com"));
}

#[test]
fn plan_tcp_hostfake_emits_fake_real_fake_sequence_for_http_host() {
    let payload = b"GET / HTTP/1.1\r\nHost: sub.example.com\r\n\r\n";
    let markers = http_marker_info(payload).expect("http markers");
    let mut group = DesyncGroup::new(0);
    group.actions.ttl = Some(9);
    group.actions.tcp_chain = vec![TcpChainStep {
        kind: TcpChainStepKind::HostFake,
        offset: OffsetExpr::marker(OffsetBase::PayloadEnd, 0),
        activation_filter: None,
        midhost_offset: Some(OffsetExpr::marker(OffsetBase::MidSld, 0)),
        fake_host_template: Some("googlevideo.com".to_string()),
        fragment_count: 0,
        min_fragment_size: 0,
        max_fragment_size: 0,
    }];

    let plan = plan_tcp(&group, payload, 23, 32, tcp_context(payload)).expect("plan hostfake");

    assert_eq!(
        plan.steps,
        vec![PlannedStep { kind: TcpChainStepKind::HostFake, start: 0, end: payload.len() as i64 }]
    );
    assert_eq!(
        plan.actions,
        vec![
            DesyncAction::Write(payload[..markers.host_start].to_vec()),
            DesyncAction::AwaitWritable,
            DesyncAction::SetTtl(9),
            DesyncAction::Write(build_hostfake_bytes(
                &payload[markers.host_start..markers.host_end],
                Some("googlevideo.com"),
                23
            )),
            DesyncAction::RestoreDefaultTtl,
            DesyncAction::SetTtl(32),
            DesyncAction::Write(payload[markers.host_start..markers.host_start + 7].to_vec()),
            DesyncAction::AwaitWritable,
            DesyncAction::Write(payload[markers.host_start + 7..markers.host_end].to_vec()),
            DesyncAction::AwaitWritable,
            DesyncAction::SetTtl(9),
            DesyncAction::Write(build_hostfake_bytes(
                &payload[markers.host_start..markers.host_end],
                Some("googlevideo.com"),
                23
            )),
            DesyncAction::RestoreDefaultTtl,
            DesyncAction::SetTtl(32),
            DesyncAction::Write(payload[markers.host_end..].to_vec()),
            DesyncAction::AwaitWritable,
        ]
    );
}

#[test]
fn hostfake_degrades_to_split_when_step_ends_before_endhost() {
    let payload = b"GET / HTTP/1.1\r\nHost: sub.example.com\r\n\r\n";
    let markers = http_marker_info(payload).expect("http markers");
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain = vec![TcpChainStep {
        kind: TcpChainStepKind::HostFake,
        offset: OffsetExpr::marker(OffsetBase::Host, 0),
        activation_filter: None,
        midhost_offset: None,
        fake_host_template: None,
        fragment_count: 0,
        min_fragment_size: 0,
        max_fragment_size: 0,
    }];

    let plan = plan_tcp(&group, payload, 9, 32, tcp_context(payload)).expect("plan degraded hostfake");

    assert_eq!(
        plan.steps,
        vec![PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: markers.host_start as i64 }]
    );
    assert_eq!(
        plan.actions,
        vec![
            DesyncAction::Write(payload[..markers.host_start].to_vec()),
            DesyncAction::AwaitWritable,
            DesyncAction::Write(payload[markers.host_start..].to_vec()),
        ]
    );
}

#[test]
fn unresolved_markers_fail_planning_safely() {
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::tls_host(0)));

    let payload = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
    assert!(plan_tcp(&group, payload, 7, 64, tcp_context(payload)).is_err());
}

#[test]
fn plan_udp_wraps_fake_burst_and_drop_sack_actions() {
    let mut group = DesyncGroup::new(0);
    group.actions.drop_sack = true;
    group.actions.udp_chain = vec![
        UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: 1, activation_filter: None },
        UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: 2, activation_filter: None },
    ];
    group.actions.ttl = Some(7);
    group.actions.fake_data = Some(b"udp-fake".to_vec());

    let actions = plan_udp(&group, b"payload", 64, udp_context(b"payload"));

    assert_eq!(
        actions,
        vec![
            DesyncAction::AttachDropSack,
            DesyncAction::SetTtl(7),
            DesyncAction::Write(b"udp-fake".to_vec()),
            DesyncAction::RestoreDefaultTtl,
            DesyncAction::SetTtl(64),
            DesyncAction::SetTtl(7),
            DesyncAction::Write(b"udp-fake".to_vec()),
            DesyncAction::Write(b"udp-fake".to_vec()),
            DesyncAction::RestoreDefaultTtl,
            DesyncAction::SetTtl(64),
            DesyncAction::Write(b"payload".to_vec()),
            DesyncAction::DetachDropSack,
        ]
    );
}

#[test]
fn plan_udp_step_activation_filter_skips_filtered_fake_bursts() {
    let mut group = DesyncGroup::new(0);
    group.actions.udp_chain = vec![
        UdpChainStep {
            kind: UdpChainStepKind::FakeBurst,
            count: 1,
            activation_filter: Some(ActivationFilter {
                round: Some(NumericRange::new(2, 4)),
                payload_size: None,
                stream_bytes: None,
            }),
        },
        UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: 2, activation_filter: None },
    ];
    group.actions.fake_data = Some(b"udp-fake".to_vec());

    let actions = plan_udp(&group, b"payload", 64, udp_context(b"payload"));

    assert_eq!(
        actions,
        vec![
            DesyncAction::SetTtl(8),
            DesyncAction::Write(b"udp-fake".to_vec()),
            DesyncAction::Write(b"udp-fake".to_vec()),
            DesyncAction::RestoreDefaultTtl,
            DesyncAction::SetTtl(64),
            DesyncAction::Write(b"payload".to_vec()),
        ]
    );
}

#[test]
fn plan_udp_uses_generated_quic_fake_initial_when_profile_is_active() {
    let mut group = DesyncGroup::new(0);
    group.actions.ttl = Some(7);
    group.actions.quic_fake_profile = QuicFakeProfile::RealisticInitial;
    group.actions.quic_fake_host = Some("video.example.test".to_string());
    group.actions.udp_chain =
        vec![UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: 1, activation_filter: None }];
    let payload = build_realistic_quic_initial(QUIC_V2_VERSION, Some("source.example.test")).expect("input quic");

    let actions = plan_udp(&group, &payload, 64, udp_context(&payload));
    let DesyncAction::Write(fake_packet) = &actions[1] else {
        panic!("expected first fake write");
    };
    let parsed = parse_quic_initial(fake_packet).expect("parse generated fake");

    assert_eq!(parsed.version, QUIC_V2_VERSION);
    assert_eq!(parsed.host(), b"video.example.test");
    assert_eq!(actions.last(), Some(&DesyncAction::Write(payload)));
}

#[test]
fn plan_udp_adaptive_profiles_tune_burst_count_and_quic_fake_payload() {
    let mut group = DesyncGroup::new(0);
    group.actions.quic_fake_profile = QuicFakeProfile::RealisticInitial;
    group.actions.quic_fake_host = Some("video.example.test".to_string());
    group.actions.udp_chain =
        vec![UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: 2, activation_filter: None }];
    let payload = build_realistic_quic_initial(QUIC_V2_VERSION, Some("source.example.test")).expect("input quic");

    let balanced = plan_udp(&group, &payload, 64, udp_context(&payload));
    assert_eq!(balanced.iter().filter(|action| matches!(action, DesyncAction::Write(_))).count(), 3,);

    let mut conservative = udp_context(&payload);
    conservative.adaptive.udp_burst_profile = Some(AdaptiveUdpBurstProfile::Conservative);
    conservative.adaptive.quic_fake_profile = Some(QuicFakeProfile::CompatDefault);
    let adaptive_actions = plan_udp(&group, &payload, 64, conservative);
    assert_eq!(adaptive_actions.iter().filter(|action| matches!(action, DesyncAction::Write(_))).count(), 2,);
    let DesyncAction::Write(fake_packet) = &adaptive_actions[1] else {
        panic!("expected fake packet write");
    };
    assert_eq!(fake_packet, &default_fake_quic_compat());
}

#[test]
fn plan_udp_falls_back_to_raw_fake_payload_for_non_quic_input() {
    let mut group = DesyncGroup::new(0);
    group.actions.quic_fake_profile = QuicFakeProfile::CompatDefault;
    group.actions.udp_chain =
        vec![UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: 1, activation_filter: None }];
    group.actions.fake_data = Some(b"udp-fake".to_vec());

    let actions = plan_udp(&group, b"payload", 64, udp_context(b"payload"));

    assert_eq!(
        actions,
        vec![
            DesyncAction::SetTtl(8),
            DesyncAction::Write(b"udp-fake".to_vec()),
            DesyncAction::RestoreDefaultTtl,
            DesyncAction::SetTtl(64),
            DesyncAction::Write(b"payload".to_vec()),
        ]
    );
}

#[test]
fn build_fake_packet_uses_selected_http_profile_when_no_raw_fake_is_set() {
    let mut group = DesyncGroup::new(0);
    group.actions.http_fake_profile = HttpFakeProfile::CloudflareGet;

    let fake = build_fake_packet(&group, b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n", 7).expect("http fake");
    let parsed = parse_http(&fake.bytes).expect("parse fake http");

    assert_eq!(parsed.host, b"www.cloudflare.com");
}

#[test]
fn build_fake_packet_uses_selected_tls_profile_when_no_raw_fake_is_set() {
    let mut group = DesyncGroup::new(0);
    group.actions.tls_fake_profile = TlsFakeProfile::GoogleChrome;

    let fake = build_fake_packet(&group, DEFAULT_FAKE_TLS, 7).expect("tls fake");
    let parsed = parse_tls(&fake.bytes).expect("parse fake tls");

    assert_eq!(parsed, b"www.google.com");
}

#[test]
fn plan_udp_uses_selected_udp_profile_when_no_raw_fake_is_set() {
    let mut group = DesyncGroup::new(0);
    group.actions.udp_fake_profile = UdpFakeProfile::DnsQuery;
    group.actions.udp_chain =
        vec![UdpChainStep { kind: UdpChainStepKind::FakeBurst, count: 1, activation_filter: None }];

    let actions = plan_udp(&group, b"payload", 64, udp_context(b"payload"));

    let DesyncAction::Write(fake_packet) = &actions[1] else {
        panic!("expected fake packet write");
    };
    assert_eq!(fake_packet.len(), 38);
    assert_eq!(&fake_packet[12..19], b"\x06update");
}
