use super::*;
use std::borrow::Cow;
use std::io;

use ripdpi_config::{
    EntropyMode, FakeOrder, FakeSeqMode, NumericRange, OffsetBase, OffsetExpr, TcpChainStep, TcpFakePayload,
    TcpFlagOverrides, TcpHostFakePayload, TcpTypedChainStep,
};
use ripdpi_desync::{
    ActivationTcpState, ActivationTransport, AdaptivePlannerHints, DesyncAction, DesyncPlan, PlannedStep, ProtoInfo,
};
use ripdpi_packets::{entropy, http_marker_info};
use ripdpi_proxy_config::ProxyDirectPathCapability;
use std::net::{Ipv4Addr, TcpListener};

use crate::activation::activation_context_from_progress;
use crate::capability_policy::{
    apply_tcp_capability_fallback, apply_tcp_capability_policy, apply_transparent_tls_family,
    transparent_tls_variant_with_seed, validate_transparent_tls_family, TransparentTlsFamilyError,
    TWO_PHASE_FIRST_WRITE_MAX, TWO_PHASE_FIRST_WRITE_MIN, TWO_PHASE_GAP_MS_MAX, TWO_PHASE_GAP_MS_MIN,
};
use crate::emissions::{
    build_ordered_fake_split_emissions, build_plain_fake_emissions, ordered_segments_from_emissions, FakeEmissionRole,
};
use crate::strategy_family::{
    await_writable_action_name, restore_ttl_action_name, set_ttl_action_name, should_fallback_ipfrag2_tcp_error_kind,
    should_fallback_seqovl_error_kind, strategy_fallback_family, write_action_name,
};
use crate::tcp_actions::execute_tcp_actions;
use crate::tcp_lowering::should_ignore_android_ttl_error;
use crate::transport_io::{
    send_oob_action_named, send_out_of_band, set_stream_ttl, strategy_execution_error, strategy_result,
    transport_result, write_payload_progress, write_strategy_payload_named, write_transport_payload,
};

mod rust_packet_seeds {
    include!(concat!(env!("CARGO_MANIFEST_DIR"), "/../ripdpi-packets/tests/rust_packet_seeds.rs"));
}

fn test_group() -> DesyncGroup {
    DesyncGroup::new(0)
}

fn test_offset() -> OffsetExpr {
    OffsetExpr::absolute(0)
}

fn with_original_flag_overrides(step: &TcpChainStep, original_flags: TcpFlagOverrides) -> TcpChainStep {
    match step.typed_step() {
        TcpTypedChainStep::Plain { kind, common, .. } => {
            TcpChainStep::from_typed_step(TcpTypedChainStep::Plain { kind, common, original_flags })
        }
        TcpTypedChainStep::Fake { kind, common, payload } => TcpChainStep::from_typed_step(TcpTypedChainStep::Fake {
            kind,
            common,
            payload: TcpFakePayload { original_flags, ..payload },
        }),
        TcpTypedChainStep::HostFake { common, payload } => TcpChainStep::from_typed_step(TcpTypedChainStep::HostFake {
            common,
            payload: TcpHostFakePayload { original_flags, ..payload },
        }),
        TcpTypedChainStep::IpFrag { common, payload, .. } => {
            TcpChainStep::from_typed_step(TcpTypedChainStep::IpFrag { common, payload, original_flags })
        }
        typed_step => TcpChainStep::from_typed_step(typed_step),
    }
}

fn connected_pair() -> (TcpStream, TcpStream) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind listener");
    let addr = listener.local_addr().expect("listener addr");
    let client = TcpStream::connect(addr).expect("connect client");
    let (server, _) = listener.accept().expect("accept client");
    (client, server)
}

fn multidisorder_chain() -> Vec<TcpChainStep> {
    vec![
        TcpChainStep::new(TcpChainStepKind::MultiDisorder, OffsetExpr::absolute(2)),
        TcpChainStep::new(TcpChainStepKind::MultiDisorder, OffsetExpr::absolute(4)),
    ]
}

fn capability_with_family(tcp_family: &str) -> ProxyDirectPathCapability {
    ProxyDirectPathCapability {
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
        tcp_family: tcp_family.to_string(),
        outcome: "TRANSPARENT_OK".to_string(),
        transport_class: Some("SNI_TLS_SUSPECT".to_string()),
        reason_code: Some("TCP_POST_CLIENT_HELLO_FAILURE".to_string()),
        cooldown_until: None,
        updated_at: 1,
    }
}

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

    assert!(!has_tcp_actions(&group));
    assert!(!should_desync_tcp(&group, in_range));
    assert!(activation_filter_matches(group.activation_filter(), in_range));
    assert!(!activation_filter_matches(group.activation_filter(), out_of_range));

    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Split, test_offset()));
    assert!(has_tcp_actions(&group));
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
        steps: vec![PlannedStep { kind: TcpChainStepKind::FakeSplit, start: 0, end: 3 }],
        proto: ProtoInfo::default(),
        actions: Vec::new(),
    };
    let terminal_fake_step_plan = DesyncPlan {
        tampered: b"payload".to_vec(),
        steps: vec![PlannedStep { kind: TcpChainStepKind::FakeSplit, start: 0, end: 7 }],
        proto: ProtoInfo::default(),
        actions: Vec::new(),
    };

    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::FakeSplit, test_offset()));
    assert!(!requires_special_tcp_execution(&group, &non_terminal_fake_step_plan, false));
    assert!(requires_special_tcp_execution(&group, &non_terminal_fake_step_plan, true));
    assert!(requires_special_tcp_execution(&group, &terminal_fake_step_plan, false));

    group.actions.tcp_chain.clear();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::FakeDisorder, test_offset()));
    let non_terminal_fake_disorder_plan = DesyncPlan {
        tampered: b"payload".to_vec(),
        steps: vec![PlannedStep { kind: TcpChainStepKind::FakeDisorder, start: 0, end: 3 }],
        proto: ProtoInfo::default(),
        actions: Vec::new(),
    };
    let terminal_fake_disorder_plan = DesyncPlan {
        tampered: b"payload".to_vec(),
        steps: vec![PlannedStep { kind: TcpChainStepKind::FakeDisorder, start: 0, end: 7 }],
        proto: ProtoInfo::default(),
        actions: Vec::new(),
    };
    assert!(!requires_special_tcp_execution(&group, &non_terminal_fake_disorder_plan, false));
    assert!(requires_special_tcp_execution(&group, &non_terminal_fake_disorder_plan, true));
    assert!(requires_special_tcp_execution(&group, &terminal_fake_disorder_plan, false));

    group.actions.tcp_chain.clear();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::Fake, test_offset()));
    assert!(requires_special_tcp_execution(&group, &non_terminal_fake_step_plan, false));

    group.actions.tcp_chain.clear();
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::MultiDisorder, test_offset()));
    group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::MultiDisorder, OffsetExpr::absolute(4)));
    assert!(requires_special_tcp_execution(&group, &non_terminal_fake_step_plan, false));
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
                PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 0, end: 2 },
                PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 3, end: 4 },
                PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 4, end: 6 },
            ],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
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
                PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 0, end: 2 },
                PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 2, end: 4 },
                PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 4, end: 5 },
            ],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
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
                PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 0, end: 2 },
                PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 2, end: 4 },
                PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 4, end: 6 },
            ],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
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
fn outbound_send_error_preserves_strategy_execution_metadata() {
    let err = strategy_execution_error(
        "set_ttl_disorder",
        "disorder",
        Some("split"),
        0,
        io::Error::from_raw_os_error(libc::EINVAL),
    );

    assert_eq!(err.kind(), io::ErrorKind::InvalidInput);
    match err {
        OutboundSendError::StrategyExecution {
            action,
            strategy_family,
            fallback,
            bytes_committed,
            source_errno,
            ..
        } => {
            assert_eq!(action, "set_ttl_disorder");
            assert_eq!(strategy_family, "disorder");
            assert_eq!(fallback, Some("split"));
            assert_eq!(bytes_committed, 0);
            assert_eq!(source_errno, Some(libc::EINVAL));
        }
        OutboundSendError::Transport(_) => panic!("expected strategy execution error"),
    }
    assert!(err.to_string().contains("desync action=set_ttl_disorder"));
}

#[test]
fn outbound_send_error_into_io_error_preserves_fallback_details() {
    let err = strategy_execution_error(
        "write_disorder",
        "disorder",
        Some("split"),
        0,
        io::Error::from_raw_os_error(libc::EROFS),
    );
    let io_error = err.into_io_error();

    assert_eq!(io_error.kind(), io::ErrorKind::ReadOnlyFilesystem);
    assert_eq!(
        io_error.get_ref().and_then(|inner| inner.downcast_ref::<OutboundSendError>()).and_then(|inner| match inner {
            OutboundSendError::StrategyExecution { fallback, .. } => *fallback,
            OutboundSendError::Transport(_) => None,
        }),
        Some("split")
    );
    assert!(io_error.get_ref().and_then(|inner| inner.downcast_ref::<OutboundSendError>()).is_some());
}

#[test]
fn android_ttl_fallback_filter_matches_capability_errors_only() {
    assert!(should_ignore_android_ttl_error(&io::Error::from_raw_os_error(libc::EROFS)));
    assert!(should_ignore_android_ttl_error(&io::Error::from_raw_os_error(libc::EINVAL)));
    assert!(!should_ignore_android_ttl_error(&io::Error::from_raw_os_error(libc::ECONNRESET)));
}

#[test]
fn android_ttl_fallback_filter_matches_strategy_execution_source_errors() {
    let err = strategy_execution_error(
        "set_ttl_disorder",
        "disorder",
        Some("split"),
        0,
        io::Error::from_raw_os_error(libc::EROFS),
    );
    assert!(should_ignore_android_ttl_error(err.source_error()));
}

// ---------------------------------------------------------------
// apply_entropy_padding
// ---------------------------------------------------------------

#[test]
fn entropy_padding_disabled_returns_borrowed() {
    let group = test_group(); // entropy_mode defaults to Disabled
    let payload = b"test payload";
    let result = apply_entropy_padding(&group, payload, None);
    assert!(matches!(result, Cow::Borrowed(_)));
    assert_eq!(&*result, payload);
}

#[test]
fn entropy_padding_popcount_mode_pads_non_exempt_payload() {
    let mut group = test_group();
    group.actions.entropy_mode = EntropyMode::Popcount;
    // 0xAA has popcount 4.0 (in GFW detection window 3.4-4.6)
    let payload = vec![0xAA; 100];
    let result = apply_entropy_padding(&group, &payload, None);
    assert!(matches!(result, Cow::Owned(_)), "should pad non-exempt payload");
    assert!(result.len() > payload.len(), "padded should be longer");
    // Padded payload should start with padding, end with original
    assert_eq!(&result[result.len() - payload.len()..], &payload[..]);
}

#[test]
fn entropy_padding_popcount_mode_skips_exempt_payload() {
    let mut group = test_group();
    group.actions.entropy_mode = EntropyMode::Popcount;
    // All zeros: popcount 0.0, already exempt
    let payload = vec![0x00; 100];
    let result = apply_entropy_padding(&group, &payload, None);
    assert!(matches!(result, Cow::Borrowed(_)), "exempt payload should not be padded");
}

#[test]
fn entropy_padding_shannon_mode_pads_high_entropy() {
    let mut group = test_group();
    group.actions.entropy_mode = EntropyMode::Shannon;
    // High entropy payload
    let payload: Vec<u8> = (0..2048).map(|i| (i % 256) as u8).collect();
    let result = apply_entropy_padding(&group, &payload, None);
    assert!(matches!(result, Cow::Owned(_)), "should pad high-entropy payload");
    assert!(result.len() > payload.len());
}

#[test]
fn entropy_padding_shannon_mode_skips_low_entropy() {
    let mut group = test_group();
    group.actions.entropy_mode = EntropyMode::Shannon;
    let payload = b"AAAAAAAAAAAAAAAAAAAAA"; // very low entropy
    let result = apply_entropy_padding(&group, payload, None);
    assert!(matches!(result, Cow::Borrowed(_)), "low entropy should not be padded");
}

#[test]
fn entropy_padding_combined_mode_works() {
    let mut group = test_group();
    group.actions.entropy_mode = EntropyMode::Combined;
    // High entropy: needs Shannon padding
    let payload: Vec<u8> = (0..2048).map(|i| (i % 256) as u8).collect();
    let result = apply_entropy_padding(&group, &payload, None);
    assert!(result.len() > payload.len(), "combined mode should pad high-entropy");
}

#[test]
fn entropy_padding_adaptive_override_takes_precedence() {
    let mut group = test_group();
    group.actions.entropy_mode = EntropyMode::Disabled; // group says disabled
                                                        // But adaptive override says Shannon
    let payload: Vec<u8> = (0..2048).map(|i| (i % 256) as u8).collect();
    let result = apply_entropy_padding(&group, &payload, Some(EntropyMode::Shannon));
    assert!(matches!(result, Cow::Owned(_)), "adaptive override should enable padding");
    assert!(result.len() > payload.len());
}

#[test]
fn entropy_padding_adaptive_override_can_disable() {
    let mut group = test_group();
    group.actions.entropy_mode = EntropyMode::Shannon; // group says Shannon
                                                       // But adaptive override says Disabled
    let payload: Vec<u8> = (0..2048).map(|i| (i % 256) as u8).collect();
    let result = apply_entropy_padding(&group, &payload, Some(EntropyMode::Disabled));
    assert!(matches!(result, Cow::Borrowed(_)), "adaptive Disabled should skip padding");
}

#[test]
fn entropy_padding_custom_shannon_target_permil() {
    let mut group = test_group();
    group.actions.entropy_mode = EntropyMode::Shannon;
    group.actions.shannon_entropy_target_permil = Some(7920); // 7.92 bits/byte
    let payload: Vec<u8> = (0..2048).map(|i| (i % 256) as u8).collect();
    let result = apply_entropy_padding(&group, &payload, None);
    assert!(matches!(result, Cow::Owned(_)));
    // Padded result should bring entropy below 7.92
    let combined_entropy = entropy::shannon_entropy(&result);
    assert!(combined_entropy <= 7.92, "expected <= 7.92, got {combined_entropy}");
}

#[test]
fn entropy_padding_custom_popcount_target_permil() {
    let mut group = test_group();
    group.actions.entropy_mode = EntropyMode::Popcount;
    group.actions.entropy_padding_target_permil = Some(3200); // 3.2 target
    let payload = vec![0xAA; 100]; // popcount 4.0
    let result = apply_entropy_padding(&group, &payload, None);
    assert!(matches!(result, Cow::Owned(_)));
    let pc = entropy::popcount_per_byte(&result);
    assert!(pc <= 3.2, "expected popcount <= 3.2, got {pc}");
}

#[test]
fn entropy_padding_preserves_original_payload_at_end() {
    let mut group = test_group();
    group.actions.entropy_mode = EntropyMode::Shannon;
    let payload: Vec<u8> = (0..512).map(|i| (i % 256) as u8).collect();
    let result = apply_entropy_padding(&group, &payload, None);
    if result.len() > payload.len() {
        let suffix = &result[result.len() - payload.len()..];
        assert_eq!(suffix, &payload[..], "original payload should be at the end");
    }
}

#[test]
fn entropy_padding_respects_max_pad_config() {
    let mut group = test_group();
    group.actions.entropy_mode = EntropyMode::Shannon;
    group.actions.entropy_padding_max = 10; // very small
    let payload: Vec<u8> = (0..4096).map(|i| (i % 256) as u8).collect();
    let result = apply_entropy_padding(&group, &payload, None);
    // Padding can be at most 10 bytes
    let padding_size = result.len() - payload.len();
    assert!(padding_size <= 10, "padding {padding_size} exceeds max 10");
}

// ---------------------------------------------------------------
// Pure helper function tests
// ---------------------------------------------------------------

#[test]
fn strategy_fallback_maps_all_families() {
    assert_eq!(strategy_fallback_family("seg_mid_sni"), Some("seg_pre_sni"));
    assert_eq!(strategy_fallback_family("rec_mid_sni"), Some("rec_pre_sni"));
    assert_eq!(strategy_fallback_family("disorder"), Some("split"));
    assert_eq!(strategy_fallback_family("seqovl"), Some("split"));
    assert_eq!(strategy_fallback_family("tlsrec_seqovl"), Some("tlsrec_split"));
    assert_eq!(strategy_fallback_family("disoob"), Some("oob"));
    assert_eq!(strategy_fallback_family("fakeddisorder"), Some("fakedsplit"));
    assert_eq!(strategy_fallback_family("split"), None);
    assert_eq!(strategy_fallback_family("oob"), None);
    assert_eq!(strategy_fallback_family("fake"), None);
    assert_eq!(strategy_fallback_family("multidisorder"), None);
    assert_eq!(strategy_fallback_family("unknown"), None);
}

#[test]
fn write_action_name_maps_all_families() {
    assert_eq!(write_action_name("split"), "write_split");
    assert_eq!(write_action_name("seg_pre_sni"), "write_split");
    assert_eq!(write_action_name("seg_mid_sni"), "write_split");
    assert_eq!(write_action_name("seg_post_sni"), "write_split");
    assert_eq!(write_action_name("rec_pre_sni"), "write_tlsrec");
    assert_eq!(write_action_name("rec_mid_sni"), "write_tlsrec");
    assert_eq!(write_action_name("two_phase_send"), "write_split");
    assert_eq!(write_action_name("seqovl"), "write_seqovl");
    assert_eq!(write_action_name("tlsrec_seqovl"), "write_seqovl");
    assert_eq!(write_action_name("disorder"), "write_disorder");
    assert_eq!(write_action_name("oob"), "write_oob");
    assert_eq!(write_action_name("disoob"), "write_disoob");
    assert_eq!(write_action_name("fake"), "write_fake");
    assert_eq!(write_action_name("fakedsplit"), "write_fakesplit");
    assert_eq!(write_action_name("fakeddisorder"), "write_fakeddisorder");
    assert_eq!(write_action_name("hostfake"), "write_hostfake");
    assert_eq!(write_action_name("unknown"), "write");
}

#[test]
fn set_ttl_action_name_maps_variants() {
    assert_eq!(set_ttl_action_name("disorder"), "set_ttl_disorder");
    assert_eq!(set_ttl_action_name("disoob"), "set_ttl_disoob");
    assert_eq!(set_ttl_action_name("fakeddisorder"), "set_ttl_fakeddisorder");
    assert_eq!(set_ttl_action_name("split"), "set_ttl");
    assert_eq!(set_ttl_action_name("oob"), "set_ttl");
}

#[test]
fn restore_ttl_action_name_maps_variants() {
    assert_eq!(restore_ttl_action_name("disorder"), "restore_default_ttl_disorder");
    assert_eq!(restore_ttl_action_name("disoob"), "restore_default_ttl_disoob");
    assert_eq!(restore_ttl_action_name("fakeddisorder"), "restore_default_ttl_fakeddisorder");
    assert_eq!(restore_ttl_action_name("split"), "restore_default_ttl");
}

#[test]
fn await_writable_action_name_maps_all() {
    assert_eq!(await_writable_action_name("split"), "await_writable_split");
    assert_eq!(await_writable_action_name("seg_pre_sni"), "await_writable_split");
    assert_eq!(await_writable_action_name("seg_mid_sni"), "await_writable_split");
    assert_eq!(await_writable_action_name("seg_post_sni"), "await_writable_split");
    assert_eq!(await_writable_action_name("rec_pre_sni"), "await_writable_tlsrec");
    assert_eq!(await_writable_action_name("rec_mid_sni"), "await_writable_tlsrec");
    assert_eq!(await_writable_action_name("two_phase_send"), "await_writable_split");
    assert_eq!(await_writable_action_name("seqovl"), "await_writable_seqovl");
    assert_eq!(await_writable_action_name("tlsrec_seqovl"), "await_writable_seqovl");
    assert_eq!(await_writable_action_name("disorder"), "await_writable_disorder");
    assert_eq!(await_writable_action_name("oob"), "await_writable_oob");
    assert_eq!(await_writable_action_name("disoob"), "await_writable_disoob");
    assert_eq!(await_writable_action_name("fakedsplit"), "await_writable_fakesplit");
    assert_eq!(await_writable_action_name("fakeddisorder"), "await_writable_fakeddisorder");
    assert_eq!(await_writable_action_name("hostfake"), "await_writable_hostfake");
    assert_eq!(await_writable_action_name("unknown"), "await_writable");
}

#[test]
fn ipfrag2_fallback_matches_expected_kinds() {
    assert!(should_fallback_ipfrag2_tcp_error_kind(io::ErrorKind::InvalidInput));
    assert!(should_fallback_ipfrag2_tcp_error_kind(io::ErrorKind::WouldBlock));
    assert!(should_fallback_ipfrag2_tcp_error_kind(io::ErrorKind::Unsupported));
    assert!(!should_fallback_ipfrag2_tcp_error_kind(io::ErrorKind::ConnectionReset));
    assert!(!should_fallback_ipfrag2_tcp_error_kind(io::ErrorKind::BrokenPipe));
}

#[test]
fn seqovl_fallback_matches_expected_kinds() {
    assert!(should_fallback_seqovl_error_kind(io::ErrorKind::InvalidInput));
    assert!(should_fallback_seqovl_error_kind(io::ErrorKind::WouldBlock));
    assert!(should_fallback_seqovl_error_kind(io::ErrorKind::Unsupported));
    assert!(should_fallback_seqovl_error_kind(io::ErrorKind::PermissionDenied));
    assert!(!should_fallback_seqovl_error_kind(io::ErrorKind::ConnectionReset));
}

#[test]
fn strategy_result_ok_passes_through() {
    let result: Result<i32, OutboundSendError> = strategy_result(Ok(42), "action", "family", Some("fallback"), 0);
    assert_eq!(result.unwrap(), 42);
}

#[test]
fn strategy_result_err_wraps_metadata() {
    let result: Result<i32, OutboundSendError> = strategy_result(
        Err(io::Error::new(io::ErrorKind::BrokenPipe, "broken")),
        "write_split",
        "split",
        Some("disorder"),
        100,
    );
    match result.unwrap_err() {
        OutboundSendError::StrategyExecution { action, strategy_family, fallback, bytes_committed, .. } => {
            assert_eq!(action, "write_split");
            assert_eq!(strategy_family, "split");
            assert_eq!(fallback, Some("disorder"));
            assert_eq!(bytes_committed, 100);
        }
        OutboundSendError::Transport(err) => panic!("expected StrategyExecution, got Transport({err})"),
    }
}

#[test]
fn transport_result_ok_passes_through() {
    let result: Result<i32, OutboundSendError> = transport_result(Ok(42));
    assert_eq!(result.unwrap(), 42);
}

#[test]
fn transport_result_err_wraps_as_transport() {
    let result: Result<i32, OutboundSendError> =
        transport_result(Err(io::Error::new(io::ErrorKind::BrokenPipe, "broken")));
    assert!(matches!(result.unwrap_err(), OutboundSendError::Transport(_)));
}

// ---------------------------------------------------------------
// Write helper tests
// ---------------------------------------------------------------

#[test]
fn write_payload_progress_full_payload() {
    let (mut client, mut server) = connected_pair();
    let payload = b"hello world test data";
    write_payload_progress(&mut client, payload).expect("write succeeds");
    let mut buf = vec![0u8; payload.len()];
    use std::io::Read;
    server.read_exact(&mut buf).expect("read succeeds");
    assert_eq!(&buf, payload);
}

#[test]
fn write_payload_progress_closed_stream_errors() {
    let (mut client, server) = connected_pair();
    drop(server);
    // Write enough data to overwhelm kernel buffers and trigger an error
    let big = vec![0u8; 1024 * 1024];
    let mut got_error = false;
    for _ in 0..16 {
        if write_payload_progress(&mut client, &big).is_err() {
            got_error = true;
            break;
        }
    }
    assert!(got_error, "expected write error after filling kernel buffer to closed peer");
}

#[test]
fn write_transport_payload_returns_byte_count() {
    let (mut client, _server) = connected_pair();
    let result = write_transport_payload(&mut client, b"hello");
    assert_eq!(result.unwrap(), 5);
}

#[test]
fn write_transport_payload_error_is_transport() {
    let (mut client, server) = connected_pair();
    drop(server);
    let big = vec![0u8; 1024 * 1024];
    let mut last_err = None;
    for _ in 0..16 {
        if let Err(err) = write_transport_payload(&mut client, &big) {
            last_err = Some(err);
            break;
        }
    }
    let err = last_err.expect("expected transport error after filling kernel buffer");
    assert!(matches!(err, OutboundSendError::Transport(_)));
}

#[test]
fn write_strategy_named_accumulates_committed() {
    let (mut client, _server) = connected_pair();
    let result = write_strategy_payload_named(&mut client, b"hello world", "write_split", "split", None, 50);
    assert_eq!(result.unwrap(), 61); // 50 + 11
}

#[test]
fn write_strategy_named_error_has_metadata() {
    let (mut client, server) = connected_pair();
    drop(server);
    let big = vec![0u8; 1024 * 1024];
    let mut last_err = None;
    for _ in 0..16 {
        if let Err(err) = write_strategy_payload_named(&mut client, &big, "write_split", "split", Some("disorder"), 50)
        {
            last_err = Some(err);
            break;
        }
    }
    match last_err.expect("expected strategy error") {
        OutboundSendError::StrategyExecution { action, strategy_family, fallback, .. } => {
            assert_eq!(action, "write_split");
            assert_eq!(strategy_family, "split");
            assert_eq!(fallback, Some("disorder"));
        }
        OutboundSendError::Transport(err) => panic!("expected StrategyExecution, got Transport({err})"),
    }
}

// ---------------------------------------------------------------
// execute_tcp_actions tests
// ---------------------------------------------------------------

fn default_ttl_unavailable() -> AtomicBool {
    AtomicBool::new(false)
}

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
fn actions_set_md5sig_errors_on_unsupported() {
    let (mut client, _server) = connected_pair();
    let unavailable = default_ttl_unavailable();
    let actions = vec![DesyncAction::SetMd5Sig { key_len: 16 }];
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
                PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: 3 },
                PlannedStep { kind: TcpChainStepKind::Split, start: 3, end: 6 },
            ],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
        },
        0,
        None,
        Some("split"),
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
            steps: vec![PlannedStep { kind: TcpChainStepKind::Split, start: -1, end: 3 }],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
        },
        0,
        None,
        Some("split"),
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
            steps: vec![PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: -1 }],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
        },
        0,
        None,
        Some("split"),
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
            steps: vec![PlannedStep { kind: TcpChainStepKind::Split, start: 4, end: 2 }],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
        },
        0,
        None,
        Some("split"),
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
            steps: vec![PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: 10 }],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
        },
        0,
        None,
        Some("split"),
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
            steps: vec![PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: 5 }],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
        },
        0,
        None,
        Some("split"),
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
            steps: vec![PlannedStep { kind: TcpChainStepKind::IpFrag2, start: 0, end: 2 }],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
        },
        0,
        None,
        Some("ipfrag2"),
        &unavailable,
    );
    assert_eq!(result.unwrap(), 5);
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
            steps: vec![PlannedStep { kind: TcpChainStepKind::IpFrag2, start: 0, end: 2 }],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
        },
        0,
        None,
        Some("ipfrag2"),
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
            steps: vec![PlannedStep { kind: TcpChainStepKind::FakeRst, start: 0, end: 4 }],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
        },
        0,
        None,
        Some("fakerst"),
        &unavailable,
    );
    assert_eq!(result.unwrap(), 4);
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
            steps: vec![PlannedStep { kind: TcpChainStepKind::HostFake, start: 0, end: markers.host_start as i64 }],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
        },
        23,
        Some(9),
        Some("hostfake"),
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
            steps: vec![PlannedStep { kind: TcpChainStepKind::HostFake, start: 0, end: markers.host_start as i64 }],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
        },
        23,
        Some(9),
        Some("hostfake"),
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
            steps: vec![PlannedStep { kind: TcpChainStepKind::FakeSplit, start: 0, end: 5 }],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
        },
        0,
        Some(9),
        Some("fakesplit"),
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
            steps: vec![PlannedStep { kind: TcpChainStepKind::FakeDisorder, start: 0, end: 5 }],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
        },
        0,
        Some(9),
        Some("fakeddisorder"),
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
            steps: vec![PlannedStep { kind: TcpChainStepKind::TlsRec, start: 0, end: 6 }],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
        },
        0,
        None,
        Some("tlsrec"),
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
                PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 0, end: 2 },
                PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 2, end: 4 },
                PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 4, end: 6 },
            ],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
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
                PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 0, end: 2 },
                PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 2, end: 4 },
                PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 4, end: 6 },
            ],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
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
                PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 0, end: 3 },
                PlannedStep { kind: TcpChainStepKind::MultiDisorder, start: 3, end: 6 },
            ],
            proto: ProtoInfo::default(),
            actions: Vec::new(),
        },
        Some("multidisorder"),
        false,
        None,
    )
    .expect_err("reject fewer than 3 planned segments");
    assert!(err.to_string().contains("multidisorder requires at least three non-empty planned segments"));
}

#[test]
fn fake_ordering_plain_fake_collapses_expected_sides() {
    let before = build_plain_fake_emissions(
        FakeOrder::AllFakesFirst,
        b"real",
        &[b"fake-a".as_slice(), b"fake-b".as_slice()],
        7,
        platform::TcpFlagOverrides::default(),
        platform::TcpFlagOverrides::default(),
    );
    let after = build_plain_fake_emissions(
        FakeOrder::AllRealsFirst,
        b"real",
        &[b"fake-a".as_slice(), b"fake-b".as_slice()],
        7,
        platform::TcpFlagOverrides::default(),
        platform::TcpFlagOverrides::default(),
    );

    assert_eq!(
        before.iter().map(|emission| emission.role).collect::<Vec<_>>(),
        vec![FakeEmissionRole::Fake, FakeEmissionRole::Fake, FakeEmissionRole::Genuine]
    );
    assert_eq!(
        after.iter().map(|emission| emission.role).collect::<Vec<_>>(),
        vec![FakeEmissionRole::Genuine, FakeEmissionRole::Fake, FakeEmissionRole::Fake]
    );
}

#[test]
fn fake_ordering_split_variants_emit_expected_order() {
    let emissions = build_ordered_fake_split_emissions(
        FakeOrder::AllFakesFirst,
        b"A",
        b"FA",
        b"B",
        b"FB",
        3,
        9,
        platform::TcpFlagOverrides::default(),
        platform::TcpFlagOverrides::default(),
    );

    let labels = emissions.iter().map(|emission| std::str::from_utf8(emission.payload).unwrap()).collect::<Vec<_>>();

    assert_eq!(labels, vec!["FA", "FB", "A", "B"]);
    assert_eq!(emissions[2].ttl, 3);
    assert_eq!(emissions[3].original_offset, 1);
}

#[test]
fn fake_sequence_sequential_advances_only_fake_offsets() {
    let emissions = build_ordered_fake_split_emissions(
        FakeOrder::BeforeEach,
        b"AAA",
        b"F1",
        b"BBBB",
        b"F222",
        5,
        9,
        platform::TcpFlagOverrides::default(),
        platform::TcpFlagOverrides::default(),
    );

    let segments = ordered_segments_from_emissions(&emissions, FakeSeqMode::Sequential);

    assert_eq!(segments[0].sequence_offset, 0);
    assert_eq!(segments[1].sequence_offset, 0);
    assert_eq!(segments[2].sequence_offset, 2);
    assert_eq!(segments[3].sequence_offset, 3);
}

#[test]
fn tcp_plan_control_fake_family_continues_with_cursor() {
    let configured_step = TcpChainStep::new(TcpChainStepKind::FakeSplit, test_offset());
    let mut cursor = 0usize;

    let result = handle_tcp_plan_step_control(
        TcpChainStepKind::FakeSplit,
        TcpStepControl::ContinueAt(7),
        &configured_step,
        0,
        2,
        20,
        &mut cursor,
    );

    assert!(matches!(result, TcpPlanLoopControl::Continue));
    assert_eq!(cursor, 7);
}

#[test]
fn tcp_plan_control_break_sets_break_cursor() {
    let configured_step = TcpChainStep::new(TcpChainStepKind::IpFrag2, test_offset());
    let mut cursor = 0usize;

    let result = handle_tcp_plan_step_control(
        TcpChainStepKind::IpFrag2,
        TcpStepControl::BreakPlan,
        &configured_step,
        0,
        1,
        42,
        &mut cursor,
    );

    assert!(matches!(result, TcpPlanLoopControl::Break));
    assert_eq!(cursor, 42);
}

#[test]
fn tcp_plan_control_default_continue_advances_to_step_end() {
    let configured_step = TcpChainStep::new(TcpChainStepKind::Split, test_offset());
    let mut cursor = 3usize;

    let result = handle_tcp_plan_step_control(
        TcpChainStepKind::Split,
        TcpStepControl::ContinueAt(9),
        &configured_step,
        0,
        1,
        99,
        &mut cursor,
    );

    assert!(matches!(result, TcpPlanLoopControl::AdvanceToStepEnd));
    assert_eq!(cursor, 3);
}

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
