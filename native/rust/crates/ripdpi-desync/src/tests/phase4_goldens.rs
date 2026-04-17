use golden_test_support::{assert_text_golden, canonicalize_json};
use serde_json::{json, Value};

use crate::first_flight_ir::TlsClientHelloIr;
use crate::{normalize_quic_initial, normalize_tls_client_hello};
use crate::{ActivationContext, ActivationTcpState, ActivationTransport, AdaptivePlannerHints};
use ripdpi_config::{DesyncGroup, UdpChainStep, UdpChainStepKind};

use super::{rust_packet_seeds, tlsrandrec_step};

fn udp_context(payload: &[u8]) -> ActivationContext {
    ActivationContext {
        round: 1,
        payload_size: payload.len() as i64,
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1) as i64,
        seqovl_supported: false,
        transport: ActivationTransport::Udp,
        tcp_segment_hint: None,
        tcp_state: ActivationTcpState::default(),
        resolved_fake_ttl: None,
        adaptive: AdaptivePlannerHints::default(),
    }
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

fn first_cipher_suite_offset(packet: &[u8]) -> usize {
    let mut cursor = 5usize + 4 + 2 + 32;
    let session_id_len = usize::from(packet[cursor]);
    cursor += 1 + session_id_len;
    cursor + 2
}

fn format_protocols(protocols: &[Vec<u8>]) -> Vec<String> {
    protocols.iter().map(|protocol| String::from_utf8_lossy(protocol).into_owned()).collect()
}

fn format_u16_values(values: &[u16]) -> Vec<String> {
    values.iter().map(|value| format!("0x{value:04x}")).collect()
}

fn tls_ir_summary(ir: &TlsClientHelloIr, record_payload_lengths: Vec<usize>) -> Value {
    json!({
        "authority": String::from_utf8_lossy(&ir.authority).into_owned(),
        "authority_span": [ir.authority_span.start, ir.authority_span.end],
        "alpn": format_protocols(&ir.alpn_protocols),
        "has_ech": ir.has_ech,
        "grease": {
            "cipher_suites": format_u16_values(&ir.grease.cipher_suites),
            "extensions": format_u16_values(&ir.grease.extensions),
            "supported_groups": format_u16_values(&ir.grease.supported_groups),
            "supported_versions": format_u16_values(&ir.grease.supported_versions),
        },
        "record_payload_lengths": record_payload_lengths,
        "record_boundaries": ir.record_boundaries.iter().map(|boundary| {
            json!({
                "header": [boundary.header.start, boundary.header.end],
                "payload": [boundary.payload.start, boundary.payload.end],
                "handshake": [boundary.handshake.start, boundary.handshake.end],
            })
        }).collect::<Vec<_>>(),
        "tcp_segment_boundaries": ir.desired.tcp_segment_boundaries,
    })
}

fn tls_summary(buffer: &[u8]) -> Value {
    let ir = normalize_tls_client_hello(buffer).expect("normalize tls client hello");
    tls_ir_summary(&ir, tls_record_lengths(buffer))
}

fn quic_summary(packet: &[u8]) -> Value {
    let ir = normalize_quic_initial(packet).expect("normalize quic initial");
    json!({
        "version": format!("0x{:08x}", ir.version),
        "packet_len": packet.len(),
        "udp_datagram_boundaries": ir.desired.udp_datagram_boundaries,
        "crypto_frame_boundaries": ir.desired.crypto_frame_boundaries,
        "crypto_frames": ir.crypto_frames.iter().map(|frame| {
            json!({
                "crypto_range": [frame.crypto_range.start, frame.crypto_range.end],
                "packet_payload_range": [frame.packet_payload_range.start, frame.packet_payload_range.end],
            })
        }).collect::<Vec<_>>(),
        "tls": tls_ir_summary(&ir.tls_client_hello, ir.tls_client_hello.record_boundaries.iter().map(|boundary| {
            boundary.payload.end - boundary.payload.start
        }).collect()),
    })
}

fn assert_phase4_golden(name: &str, value: &Value) {
    let actual = serde_json::to_string_pretty(value).expect("serialize golden summary");
    let actual = canonicalize_json(&actual).expect("canonicalize golden summary");
    assert_text_golden(env!("CARGO_MANIFEST_DIR"), &format!("tests/golden/{name}.json"), &actual);
}

#[test]
fn phase4_tls_record_fragmentation_golden() {
    let payload = rust_packet_seeds::tls_client_hello();
    let payload_len = payload.len() - 5;
    let marker = (payload_len - 96) as i64;
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain = vec![tlsrandrec_step(marker, 3, 32, 32)];

    let tampered = crate::apply_tamper(&group, &payload, 7).expect("apply tlsrandrec");

    assert_phase4_golden(
        "phase4_tls_record_fragmentation",
        &json!({
            "seed": 7,
            "input": tls_summary(&payload),
            "rewritten": tls_summary(&tampered.bytes),
        }),
    );
}

#[test]
fn phase4_tls_alpn_preservation_golden() {
    let payload = rust_packet_seeds::tls_client_hello();
    let payload_len = payload.len() - 5;
    let marker = (payload_len - 96) as i64;
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain = vec![tlsrandrec_step(marker, 3, 32, 32)];

    let input = tls_summary(&payload);
    let tampered = crate::apply_tamper(&group, &payload, 7).expect("apply tlsrandrec");
    let rewritten = tls_summary(&tampered.bytes);

    assert_phase4_golden(
        "phase4_tls_alpn_preservation",
        &json!({
            "input_alpn": input["alpn"].clone(),
            "rewritten_alpn": rewritten["alpn"].clone(),
            "input_authority": input["authority"].clone(),
            "rewritten_authority": rewritten["authority"].clone(),
            "rewritten_record_payload_lengths": rewritten["record_payload_lengths"].clone(),
        }),
    );
}

#[test]
fn phase4_quic_crypto_split_golden() {
    let mut group = DesyncGroup::new(0);
    group.actions.udp_chain = vec![UdpChainStep {
        kind: UdpChainStepKind::QuicCryptoSplit,
        count: 1,
        split_bytes: 0,
        activation_filter: None,
        ip_frag_disorder: false,
        ipv6_hop_by_hop: false,
        ipv6_dest_opt: false,
        ipv6_dest_opt2: false,
        ipv6_frag_next_override: None,
    }];
    let payload =
        ripdpi_packets::build_realistic_quic_initial(ripdpi_packets::QUIC_V2_VERSION, Some("docs.example.test"))
            .expect("input quic");
    let actions = crate::plan_udp(&group, &payload, 64, udp_context(&payload));
    let tampered = match &actions[1] {
        crate::DesyncAction::Write(packet) => packet.clone(),
        other => panic!("expected quic fake write, got {other:?}"),
    };

    assert_phase4_golden(
        "phase4_quic_crypto_split",
        &json!({
            "input": quic_summary(&payload),
            "rewritten": quic_summary(&tampered),
        }),
    );
}

#[test]
fn phase4_tls_ech_grease_preservation_golden() {
    let mut payload = rust_packet_seeds::tls_client_hello_ech();
    let cipher_suite_offset = first_cipher_suite_offset(&payload);
    payload[cipher_suite_offset..cipher_suite_offset + 2].copy_from_slice(&0x0a0au16.to_be_bytes());
    let payload_len = payload.len() - 5;
    let marker = (payload_len - 96) as i64;
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain = vec![tlsrandrec_step(marker, 3, 32, 32)];

    let tampered = crate::apply_tamper(&group, &payload, 7).expect("apply tlsrandrec ech");

    assert_phase4_golden(
        "phase4_tls_ech_grease_preservation",
        &json!({
            "input": tls_summary(&payload),
            "rewritten": tls_summary(&tampered.bytes),
        }),
    );
}
