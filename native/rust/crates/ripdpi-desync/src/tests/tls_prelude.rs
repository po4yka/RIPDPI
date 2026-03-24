use super::*;

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
