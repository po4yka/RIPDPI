use super::*;

fn tcp_context_with_hint(payload: &[u8], tcp_segment_hint: TcpSegmentHint) -> ActivationContext {
    ActivationContext { tcp_segment_hint: Some(tcp_segment_hint), ..tcp_context(payload) }
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
        TcpSegmentHint { snd_mss: None, advmss: Some(63), pmtu: Some(1500), ip_header_overhead: 40 }.adaptive_budget(),
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

    assert_eq!(plan.steps, vec![PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: markers.host_end as i64 }]);
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

    let plan = plan_tcp(&group, DEFAULT_FAKE_HTTP, 7, 64, tcp_context(DEFAULT_FAKE_HTTP)).expect("cursor-aware plan");

    assert_eq!(plan.steps[0], PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: markers.host_start as i64 });
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
fn plan_tcp_fakedsplit_keeps_fake_step_when_split_is_valid() {
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain = vec![TcpChainStep::new(TcpChainStepKind::FakeSplit, split_expr(4))];
    let payload = b"abcdefgh";

    let plan = plan_tcp(&group, payload, 5, 32, tcp_context(payload)).expect("plan fakedsplit tcp");

    assert_eq!(plan.steps, vec![PlannedStep { kind: TcpChainStepKind::FakeSplit, start: 0, end: 4 }]);
    assert_eq!(
        plan.actions,
        vec![DesyncAction::Write(b"abcd".to_vec()), DesyncAction::AwaitWritable, DesyncAction::Write(b"efgh".to_vec()),]
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
            DesyncAction::SetTtl(8),
            DesyncAction::Write(b"abc".to_vec()),
            DesyncAction::AwaitWritable,
            DesyncAction::RestoreDefaultTtl,
            DesyncAction::SetTtl(32),
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
            DesyncAction::SetTtl(8),
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
            DesyncAction::SetTtl(8),
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
fn plan_tcp_supports_mixed_tls_preludes_before_send_steps() {
    let payload_len = DEFAULT_FAKE_TLS.len() - 5;
    let marker = (payload_len - 96) as i64;
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain = vec![
        TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::marker(OffsetBase::ExtLen, 0)),
        tlsrandrec_step(marker, 3, 32, 32),
        TcpChainStep::new(TcpChainStepKind::Split, split_expr(4)),
    ];

    let plan = plan_tcp(&group, DEFAULT_FAKE_TLS, 7, 64, tcp_context(DEFAULT_FAKE_TLS)).expect("mixed tls preludes");

    assert_eq!(plan.steps, vec![PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: 4 }]);
    assert_eq!(tls_record_lengths(&plan.tampered), vec![payload_len - 96, 32, 32, 32]);
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

    assert_eq!(plan.steps, vec![PlannedStep { kind: TcpChainStepKind::HostFake, start: 0, end: payload.len() as i64 }]);
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
