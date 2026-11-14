use super::*;

const TLS12: &[u8] = include_bytes!("../../../ripdpi-packets/tests/fixtures/curl_tls12_example_com.bin");
const TLS13: &[u8] = include_bytes!("../../../ripdpi-packets/tests/fixtures/curl_tls13_example_com.bin");
const ECH: &[u8] = include_bytes!("../../../ripdpi-packets/src/fake_profiles/tls_clienthello_www_google_com.bin");

fn record_stream(header: &[u8], payload: &[u8], boundaries: &[usize]) -> Vec<u8> {
    let mut bytes = Vec::new();
    let mut start = 0;
    for end in boundaries.iter().copied().chain([payload.len()]) {
        bytes.extend_from_slice(header);
        bytes.extend_from_slice(&u16::try_from(end - start).unwrap().to_be_bytes());
        bytes.extend_from_slice(&payload[start..end]);
        start = end;
    }
    bytes
}

fn reassemble(bytes: &[u8], header: &[u8]) -> (Vec<usize>, Vec<u8>) {
    let mut rest = bytes;
    let mut lengths = Vec::new();
    let mut payload = Vec::new();
    while !rest.is_empty() {
        assert!(rest.len() >= 5);
        assert_eq!(&rest[..3], header);
        let length = usize::from(u16::from_be_bytes([rest[3], rest[4]]));
        assert!((1..=16_384).contains(&length));
        let (fragment, next) = rest[5..].split_at(length);
        payload.extend_from_slice(fragment);
        lengths.push(length);
        rest = next;
    }
    (lengths, payload)
}

#[test]
fn tlsrec_preserves_captured_client_hello_at_every_input_record_split() {
    // Fixed payload offsets from the captured ClientHello fields, not the parser under test.
    for (source, marker) in [(TLS12, 105usize), (TLS13, 89), (ECH, 115)] {
        let original = &source[5..];
        assert_eq!(usize::from(u16::from_be_bytes([source[3], source[4]])), original.len());
        assert_eq!(
            ((usize::from(original[1]) << 16) | (usize::from(original[2]) << 8) | usize::from(original[3])) + 4,
            original.len()
        );
        let mut group = DesyncGroup::new(0);
        group.actions.tcp_chain =
            vec![TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::marker(OffsetBase::SniExt, 0))];
        for split in 1..original.len() {
            let input = record_stream(&source[..3], original, &[split]);
            let result = apply_tamper(&group, &input, 7).expect("complete multi-record hello");
            let (lengths, rebuilt) = reassemble(&result.bytes, &source[..3]);
            let mut ends = vec![split, marker, original.len()];
            ends.sort_unstable();
            ends.dedup();
            let expected = ends
                .iter()
                .scan(0, |start, &end| {
                    let length = end - *start;
                    *start = end;
                    Some(length)
                })
                .collect::<Vec<_>>();
            assert_eq!(result.tls_prelude.applied_count, usize::from(split != marker), "split {split}");
            assert_eq!(lengths, expected, "split {split}");
            assert_eq!(rebuilt, original, "split {split}");
        }
    }
}

#[test]
fn tlsrec_preserves_many_initial_record_boundaries() {
    let original = &TLS13[5..];
    let input = record_stream(&TLS13[..3], original, &[1, 2, 3, 38, 70, 93, 95, 119, 239]);
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain =
        vec![TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::marker(OffsetBase::SniExt, 0))];

    let result = apply_tamper(&group, &input, 7).expect("many input records");

    let (lengths, payload) = reassemble(&result.bytes, &TLS13[..3]);
    assert_eq!(result.tls_prelude.applied_count, 1);
    assert_eq!(lengths, vec![1, 1, 1, 35, 32, 19, 4, 2, 24, 120, 1]);
    assert_eq!(payload, original);
}

#[test]
fn tlsrec_preserves_the_last_record_payload_and_following_wire_records() {
    let mut payload = TLS13[5..].to_vec();
    payload.extend_from_slice(&[0x0b, 0, 0, 0]);
    let mut input = record_stream(&TLS13[..3], &payload, &[40]);
    let mut tail = record_stream(&TLS13[..3], &[0x0b, 0, 0, 0], &[]);
    tail.extend_from_slice(&[0x14, 0x03, 0x03, 0, 1, 1, 0x17, 0x03, 0x03, 0, 2, 7, 9]);
    input.extend_from_slice(&tail);
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain =
        vec![TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::marker(OffsetBase::SniExt, 0))];

    let result = apply_tamper(&group, &input, 7).expect("coalesced payload");

    assert_eq!(result.tls_prelude.applied_count, 1);
    assert!(result.bytes.ends_with(&tail));
    let (lengths, rebuilt) = reassemble(&result.bytes[..result.bytes.len() - tail.len()], &TLS13[..3]);
    assert_eq!(lengths, vec![40, 49, payload.len() - 89]);
    assert_eq!(rebuilt, payload);
}

#[test]
fn tlsrec_leaves_incomplete_contributing_records_unchanged() {
    let mut input = record_stream(&TLS13[..3], &TLS13[5..], &[120]);
    input.pop();
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain =
        vec![TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::marker(OffsetBase::SniExt, 0))];

    let result = apply_tamper(&group, &input, 7).expect("incomplete second record");

    assert_eq!(result.tls_prelude.applied_count, 0);
    assert_eq!(result.bytes, input);
}

#[test]
fn tlsrec_ech_filter_accepts_a_client_hello_with_split_sni() {
    assert_eq!(&ECH[125..139], b"www.google.com");
    let input = record_stream(&ECH[..3], &ECH[5..], &[122]);
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain = vec![
        TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::host(1))
            .with_activation_filter(Some(ActivationFilter { tcp_has_ech: Some(true), ..ActivationFilter::default() })),
    ];

    let result = apply_tamper(&group, &input, 7).expect("ECH with split SNI");

    assert_eq!(result.tls_prelude.applied_count, 1);
    let (lengths, payload) = reassemble(&result.bytes, &ECH[..3]);
    assert_eq!(lengths, vec![121, 1, ECH.len() - 5 - 122]);
    assert_eq!(payload, ECH[5..]);
}

#[test]
fn tlsrec_keeps_client_hello_shaped_non_tls_envelopes_unchanged() {
    // This checks the prelude boundary. ECH metadata has separate caller regressions.
    let mut group = DesyncGroup::new(0);
    group.actions.tcp_chain = vec![
        TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::host(1))
            .with_activation_filter(Some(ActivationFilter { tcp_has_ech: Some(true), ..ActivationFilter::default() })),
    ];
    for (offset, byte) in [(0, 0x17), (1, 0x02)] {
        let mut input = ECH.to_vec();
        input[offset] = byte;
        let result = apply_tamper(&group, &input, 7).expect("non-TLS envelope");
        assert_eq!(result.tls_prelude.applied_count, 0);
        assert_eq!(result.bytes, input);
    }
}

#[test]
fn tlsminor_composes_with_each_prelude_on_fragmented_client_hello() {
    for (source, sni_marker) in [(TLS12, 105usize), (TLS13, 89)] {
        let original = &source[5..];
        for split in [1, sni_marker + 7, original.len() - 1] {
            let input = record_stream(&source[..3], original, &[split]);
            for minor in [3, 0xff] {
                for step in [
                    TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::absolute(32)),
                    tlsrandrec_step(32, 3, 1, 4096),
                ] {
                    let mut group = DesyncGroup::new(0);
                    group.actions.tlsminor = Some(minor);
                    group.actions.tcp_chain = vec![step];
                    let result = apply_tamper(&group, &input, 7).expect("version and prelude");
                    assert_eq!(result.tls_prelude.applied_count, 1);
                    let (_, rebuilt) = reassemble(&result.bytes, &[0x16, 0x03, minor]);
                    assert_eq!(rebuilt, original);
                }
            }
        }
    }
}

#[test]
fn tlsminor_alone_changes_only_the_first_record_header() {
    let input = record_stream(&TLS13[..3], &TLS13[5..], &[1, 40, 95]);
    let mut group = DesyncGroup::new(0);
    group.actions.tlsminor = Some(3);
    let result = apply_tamper(&group, &input, 7).expect("version override");
    let mut expected = input;
    expected[2] = 3;
    assert_eq!(result.bytes, expected);
}
