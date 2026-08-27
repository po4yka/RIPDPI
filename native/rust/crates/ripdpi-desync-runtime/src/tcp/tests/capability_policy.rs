use super::*;
use ripdpi_desync::TlsPreludeApplication;

#[test]
fn tcp_desync_helpers_require_actionable_groups_and_matching_rounds() {
    let mut group = test_group();
    group.set_round_activation(Some(NumericRange::new(2, 4)));
    let in_range = ActivationContext {
        round: 3,
        payload_size: 16,
        stream_start: 0,
        stream_end: 15,
        seqovl_supported: false,
        transport: ActivationTransport::Tcp,
        tcp_segment_hint: None,
        tcp_state: ActivationTcpState::default(),
        resolved_fake_ttl: None,
        adaptive: AdaptivePlannerHints::default(),
    };
    let out_of_range = ActivationContext { round: 5, ..in_range };

    assert!(!has_tcp_actions(&group, in_range));
    assert!(!should_desync_tcp(&group, in_range));
    assert!(activation_filter_matches(group.activation_filter(), in_range));
    assert!(!activation_filter_matches(group.activation_filter(), out_of_range));

    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, test_offset()));
    assert!(has_tcp_actions(&group, in_range));
    assert!(should_desync_tcp(&group, in_range));
    assert!(!should_desync_tcp(&group, out_of_range));
}

#[test]
fn activation_context_from_progress_maps_tcp_state_and_ech_from_payload() {
    let payload = rust_packet_seeds::tls_client_hello_ech();
    let progress = OutboundProgress {
        round: 2,
        payload_size: payload.len(),
        stream_start: 32,
        stream_end: 32 + payload.len() - 1,
    };
    let context = activation_context_from_progress(
        progress,
        ActivationTransport::Tcp,
        Some(&payload),
        Some(ripdpi_desync::TcpSegmentHint {
            snd_mss: Some(1300),
            advmss: Some(1400),
            pmtu: Some(1500),
            ip_header_overhead: 40,
        }),
        Some(platform::TcpActivationState { has_timestamp: Some(true), window_size: Some(2048), mss: None }),
        Some(9),
        AdaptivePlannerHints::default(),
    );

    assert_eq!(context.round, 2);
    assert_eq!(context.tcp_state.has_timestamp, Some(true));
    assert_eq!(context.tcp_state.has_ech, Some(true));
    assert_eq!(context.tcp_state.window_size, Some(2048));
    assert_eq!(context.tcp_state.mss, Some(1300));
    assert_eq!(context.resolved_fake_ttl, Some(9));
}

#[test]
fn special_tcp_execution_includes_fake_approximation_steps() {
    let mut group = test_group();
    let non_terminal_fake_step_plan = DesyncPlan {
        tampered: b"payload".to_vec(),
        steps: vec![PlannedStep {
            kind: TcpChainStepKind::FakeSplit,
            start: 0,
            end: 3,
            source_send_step_index: Some(0),
        }],
        proto: ProtoInfo::default(),
        actions: Vec::new(),
        tls_prelude: TlsPreludeApplication::default(),
    };
    let terminal_fake_step_plan = DesyncPlan {
        tampered: b"payload".to_vec(),
        steps: vec![PlannedStep {
            kind: TcpChainStepKind::FakeSplit,
            start: 0,
            end: 7,
            source_send_step_index: Some(0),
        }],
        proto: ProtoInfo::default(),
        actions: Vec::new(),
        tls_prelude: TlsPreludeApplication::default(),
    };

    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::FakeSplit, test_offset()));
    assert!(!requires_special_tcp_execution(&group, &non_terminal_fake_step_plan, false));
    assert!(requires_special_tcp_execution(&group, &non_terminal_fake_step_plan, true));
    assert!(requires_special_tcp_execution(&group, &terminal_fake_step_plan, false));

    group.actions.tcp_chain.clear();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::FakeDisorder, test_offset()));
    let non_terminal_fake_disorder_plan = DesyncPlan {
        tampered: b"payload".to_vec(),
        steps: vec![PlannedStep {
            kind: TcpChainStepKind::FakeDisorder,
            start: 0,
            end: 3,
            source_send_step_index: Some(0),
        }],
        proto: ProtoInfo::default(),
        actions: Vec::new(),
        tls_prelude: TlsPreludeApplication::default(),
    };
    let terminal_fake_disorder_plan = DesyncPlan {
        tampered: b"payload".to_vec(),
        steps: vec![PlannedStep {
            kind: TcpChainStepKind::FakeDisorder,
            start: 0,
            end: 7,
            source_send_step_index: Some(0),
        }],
        proto: ProtoInfo::default(),
        actions: Vec::new(),
        tls_prelude: TlsPreludeApplication::default(),
    };
    assert!(!requires_special_tcp_execution(&group, &non_terminal_fake_disorder_plan, false));
    assert!(requires_special_tcp_execution(&group, &non_terminal_fake_disorder_plan, true));
    assert!(requires_special_tcp_execution(&group, &terminal_fake_disorder_plan, false));

    group.actions.tcp_chain.clear();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Fake, test_offset()));
    let fake_plan = DesyncPlan {
        tampered: b"payload".to_vec(),
        steps: vec![PlannedStep { kind: TcpChainStepKind::Fake, start: 0, end: 3, source_send_step_index: Some(0) }],
        proto: ProtoInfo::default(),
        actions: Vec::new(),
        tls_prelude: TlsPreludeApplication::default(),
    };
    assert!(requires_special_tcp_execution(&group, &fake_plan, false));

    group.actions.tcp_chain.clear();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::MultiDisorder, test_offset()));
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::MultiDisorder, OffsetExpr::absolute(4)));
    let multidisorder_plan = DesyncPlan {
        tampered: b"payload".to_vec(),
        steps: vec![
            PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 0, end: 3, source_send_step_index: Some(0) },
            PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 3, end: 6, source_send_step_index: Some(1) },
        ],
        proto: ProtoInfo::default(),
        actions: Vec::new(),
        tls_prelude: TlsPreludeApplication::default(),
    };
    assert!(requires_special_tcp_execution(&group, &multidisorder_plan, false));
}

#[test]
fn seqovl_strategy_family_maps_to_seqovl_actions_and_split_fallbacks() {
    let mut group = test_group();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::SeqOverlap, test_offset()));

    assert_eq!(primary_tcp_strategy_family(&group), Some("seqovl"));
    assert_eq!(strategy_fallback_family("seqovl"), Some("split"));
    assert_eq!(write_action_name("seqovl"), "write_seqovl");
    assert_eq!(await_writable_action_name("seqovl"), "await_writable_seqovl");

    group.actions.tcp_chain.insert(0, TcpChainStep::new(TcpChainStepKind::TlsRec, test_offset()));

    assert_eq!(primary_tcp_strategy_family(&group), Some("tlsrec_seqovl"));
    assert_eq!(strategy_fallback_family("tlsrec_seqovl"), Some("tlsrec_split"));
    assert_eq!(write_action_name("tlsrec_seqovl"), "write_seqovl");
    assert_eq!(await_writable_action_name("tlsrec_seqovl"), "await_writable_seqovl");
}

#[test]
fn multidisorder_strategy_family_maps_tlsrec_variant_without_fallback() {
    let mut group = test_group();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::MultiDisorder, test_offset()));
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::MultiDisorder, OffsetExpr::absolute(4)));

    assert_eq!(primary_tcp_strategy_family(&group), Some("multidisorder"));
    assert_eq!(strategy_fallback_family("multidisorder"), None);

    group.actions.tcp_chain.insert(0, TcpChainStep::new(TcpChainStepKind::TlsRec, test_offset()));
    assert_eq!(primary_tcp_strategy_family(&group), Some("tlsrec_multidisorder"));
    assert_eq!(strategy_fallback_family("tlsrec_multidisorder"), None);
}

#[test]
fn tcp_capability_fallback_rewrites_seqovl_to_tlsrec_split_and_disables_fake_timestamp() {
    let mut group = test_group();
    group.actions.fake_tcp_timestamp_enabled = true;
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::TlsRec, test_offset()));
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::SeqOverlap, test_offset()));
    let capability = ProxyDirectPathCapability {
        authority: "example.org:443".to_string(),
        quic_usable: None,
        udp_usable: None,
        fallback_required: Some(true),
        repeated_handshake_failure_class: Some("tcp_reset".to_string()),
        transport_policy_version: 0,
        ip_set_digest: String::new(),
        dns_classification: None,
        quic_mode: "ALLOW".to_string(),
        preferred_stack: "H2".to_string(),
        dns_mode: "SYSTEM".to_string(),
        tcp_family: "REC_PRE_SNI".to_string(),
        outcome: "TRANSPARENT_OK".to_string(),
        transport_class: Some("SNI_TLS_SUSPECT".to_string()),
        reason_code: Some("TCP_POST_CLIENT_HELLO_FAILURE".to_string()),
        cooldown_until: None,
        updated_at: 1,
    };

    let adjusted = apply_tcp_capability_fallback(&group, Some(&capability)).into_owned();

    assert_eq!(
        strategy_fallback_family(primary_tcp_strategy_family(&group).expect("strategy family")),
        Some("tlsrec_split")
    );
    assert_eq!(adjusted.actions.tcp_chain[1].kind(), TcpChainStepKind::Split);
    assert!(!adjusted.actions.fake_tcp_timestamp_enabled);
}

#[test]
fn tcp_capability_fallback_leaves_group_unchanged_without_fallback_signal() {
    let mut group = test_group();
    group.actions.fake_tcp_timestamp_enabled = true;
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Disorder, test_offset()));
    let capability = ProxyDirectPathCapability {
        authority: "example.org:443".to_string(),
        quic_usable: Some(true),
        udp_usable: Some(true),
        fallback_required: Some(false),
        repeated_handshake_failure_class: None,
        transport_policy_version: 0,
        ip_set_digest: String::new(),
        dns_classification: None,
        quic_mode: "ALLOW".to_string(),
        preferred_stack: "H3".to_string(),
        dns_mode: "SYSTEM".to_string(),
        tcp_family: "NONE".to_string(),
        outcome: "TRANSPARENT_OK".to_string(),
        transport_class: None,
        reason_code: None,
        cooldown_until: None,
        updated_at: 1,
    };

    let adjusted = apply_tcp_capability_fallback(&group, Some(&capability));

    assert!(matches!(adjusted, Cow::Borrowed(_)));
}

#[test]
fn tcp_capability_policy_injects_seg_mid_sni_family_for_first_client_hello() {
    let payload = rust_packet_seeds::tls_client_hello();
    let mut group = test_group();
    group.actions.fake_tcp_timestamp_enabled = true;
    let capability = capability_with_family("SEG_MID_SNI");

    let (adjusted, strategy_family) = apply_tcp_capability_policy(
        &group,
        Some(&capability),
        &payload,
        OutboundProgress {
            round: 1,
            payload_size: payload.len(),
            stream_start: 0,
            stream_end: payload.len().saturating_sub(1),
        },
    );
    let adjusted = adjusted.into_owned();

    assert_eq!(strategy_family, Some("seg_mid_sni"));
    assert_eq!(adjusted.actions.tcp_chain.len(), 1);
    assert_eq!(adjusted.actions.tcp_chain[0].kind(), TcpChainStepKind::Split);
    assert_eq!(adjusted.actions.tcp_chain[0].offset().base, OffsetBase::MidSld);
    assert_eq!(adjusted.actions.mod_http, 0);
    assert!(!adjusted.actions.fake_tcp_timestamp_enabled);
}

#[test]
fn tcp_capability_policy_injects_seg_post_sni_family_for_first_client_hello() {
    let payload = rust_packet_seeds::tls_client_hello();
    let capability = capability_with_family("SEG_POST_SNI");
    let group = test_group();

    let (adjusted, strategy_family) = apply_tcp_capability_policy(
        &group,
        Some(&capability),
        &payload,
        OutboundProgress {
            round: 1,
            payload_size: payload.len(),
            stream_start: 0,
            stream_end: payload.len().saturating_sub(1),
        },
    );
    let adjusted = adjusted.into_owned();

    assert_eq!(strategy_family, Some("seg_post_sni"));
    assert_eq!(adjusted.actions.tcp_chain.len(), 1);
    assert_eq!(adjusted.actions.tcp_chain[0].kind(), TcpChainStepKind::Split);
    assert_eq!(adjusted.actions.tcp_chain[0].offset().base, OffsetBase::EndHost);
}

#[test]
fn tcp_capability_policy_injects_rec_pre_sni_family_for_first_client_hello() {
    let payload = rust_packet_seeds::tls_client_hello();
    let capability = capability_with_family("REC_PRE_SNI");
    let group = test_group();

    let (adjusted, strategy_family) = apply_tcp_capability_policy(
        &group,
        Some(&capability),
        &payload,
        OutboundProgress {
            round: 1,
            payload_size: payload.len(),
            stream_start: 0,
            stream_end: payload.len().saturating_sub(1),
        },
    );
    let adjusted = adjusted.into_owned();

    assert_eq!(strategy_family, Some("rec_pre_sni"));
    assert_eq!(adjusted.actions.tcp_chain.len(), 1);
    assert_eq!(adjusted.actions.tcp_chain[0].kind(), TcpChainStepKind::TlsRec);
    assert_eq!(adjusted.actions.tcp_chain[0].offset().base, OffsetBase::SniExt);
}

#[test]
fn tcp_capability_policy_injects_two_phase_send_family_for_first_client_hello() {
    let payload = rust_packet_seeds::tls_client_hello();
    let capability = capability_with_family("TWO_PHASE_SEND");
    let group = test_group();

    let (adjusted, strategy_family) = apply_tcp_capability_policy(
        &group,
        Some(&capability),
        &payload,
        OutboundProgress {
            round: 1,
            payload_size: payload.len(),
            stream_start: 0,
            stream_end: payload.len().saturating_sub(1),
        },
    );
    let adjusted = adjusted.into_owned();
    let step = &adjusted.actions.tcp_chain[0];

    assert_eq!(strategy_family, Some("two_phase_send"));
    assert_eq!(step.kind(), TcpChainStepKind::Split);
    assert_eq!(step.offset().base, OffsetBase::Abs);
    assert!(step.offset().delta >= TWO_PHASE_FIRST_WRITE_MIN as i64);
    assert!(step.offset().delta < payload.len() as i64);
    assert!(
        (u32::from(TWO_PHASE_GAP_MS_MIN)..=u32::from(TWO_PHASE_GAP_MS_MAX)).contains(&step.inter_segment_delay_ms())
    );
}

#[test]
fn transparent_tls_variant_generation_covers_post_sni_and_two_phase() {
    let payload = rust_packet_seeds::tls_client_hello();

    let seg_post = transparent_tls_variant_with_seed("seg_post_sni", &payload, 17).expect("seg post variant");
    assert!((0..=2).contains(&seg_post.offset_delta));

    let two_phase = transparent_tls_variant_with_seed("two_phase_send", &payload, 29).expect("two phase variant");
    assert!(
        matches!(two_phase.first_write_len, Some(value) if (TWO_PHASE_FIRST_WRITE_MIN..=TWO_PHASE_FIRST_WRITE_MAX).contains(&value))
    );
    assert!(
        matches!(two_phase.phase_gap_ms, Some(value) if (TWO_PHASE_GAP_MS_MIN..=TWO_PHASE_GAP_MS_MAX).contains(&value))
    );
}

#[test]
fn transparent_tls_invariant_rejects_too_short_two_phase_payloads() {
    let payload = vec![0x16; TWO_PHASE_FIRST_WRITE_MIN];
    let error = transparent_tls_variant_with_seed("two_phase_send", &payload, 7).expect_err("short payload must fail");

    assert_eq!(error, TransparentTlsFamilyError::InvalidBoundary);
}

#[test]
fn transparent_tls_invariant_preserves_plaintext_for_record_and_two_phase_families() {
    let payload = rust_packet_seeds::tls_client_hello();

    let record_group =
        apply_transparent_tls_family(&test_group(), "rec_mid_sni", &payload).expect("record family should validate");
    validate_transparent_tls_family(&payload, "rec_mid_sni", &record_group).expect("record invariant");

    let two_phase_group =
        apply_transparent_tls_family(&test_group(), "two_phase_send", &payload).expect("two phase should validate");
    validate_transparent_tls_family(&payload, "two_phase_send", &two_phase_group).expect("two phase invariant");
}

#[test]
fn tcp_capability_policy_skips_non_first_or_non_transparent_tls_arm_requests() {
    let payload = rust_packet_seeds::tls_client_hello();
    let mut capability = capability_with_family("SEG_PRE_SNI");
    capability.outcome = "NO_DIRECT_SOLUTION".to_string();
    let group = test_group();

    let (non_transparent, strategy_family) = apply_tcp_capability_policy(
        &group,
        Some(&capability),
        &payload,
        OutboundProgress {
            round: 1,
            payload_size: payload.len(),
            stream_start: 0,
            stream_end: payload.len().saturating_sub(1),
        },
    );
    assert!(matches!(non_transparent, Cow::Borrowed(_)));
    assert_eq!(strategy_family, None);

    let capability = capability_with_family("SEG_PRE_SNI");
    let group = test_group();
    let (not_first, strategy_family) = apply_tcp_capability_policy(
        &group,
        Some(&capability),
        &payload,
        OutboundProgress {
            round: 2,
            payload_size: payload.len(),
            stream_start: payload.len(),
            stream_end: payload.len().saturating_mul(2).saturating_sub(1),
        },
    );
    assert!(matches!(not_first, Cow::Borrowed(_)));
    assert_eq!(strategy_family, None);

    let mut explicit_group = test_group();
    explicit_group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Disorder, test_offset()));
    let capability = capability_with_family("SEG_PRE_SNI");
    let (explicit_group, strategy_family) = apply_tcp_capability_policy(
        &explicit_group,
        Some(&capability),
        &payload,
        OutboundProgress {
            round: 1,
            payload_size: payload.len(),
            stream_start: 0,
            stream_end: payload.len().saturating_sub(1),
        },
    );
    let explicit_group = explicit_group.into_owned();
    assert_eq!(explicit_group.actions.tcp_chain[0].kind(), TcpChainStepKind::Split);
    assert_eq!(strategy_family, None);
}
