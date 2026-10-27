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
    TWO_PHASE_FIRST_WRITE_MAX, TWO_PHASE_FIRST_WRITE_MIN, TWO_PHASE_GAP_MS_MAX, TWO_PHASE_GAP_MS_MIN,
    TransparentTlsFamilyError, apply_tcp_capability_fallback, apply_tcp_capability_policy,
    apply_transparent_tls_family, transparent_tls_variant_with_seed, validate_transparent_tls_family,
};
use crate::emissions::{
    FakeEmissionRole, build_ordered_fake_split_emissions, build_plain_fake_emissions, ordered_segments_from_emissions,
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

fn default_ttl_unavailable() -> AtomicBool {
    AtomicBool::new(false)
}

mod action_execution;
mod capability_policy;
mod entropy_padding;
mod errors_and_results;
mod fake_ordering;
mod outcome_receipt;
mod special_plan;
mod strategy_metadata;
