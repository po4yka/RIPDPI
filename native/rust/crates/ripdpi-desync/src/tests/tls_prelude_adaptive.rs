use super::*;

pub(super) fn captured_hellos() -> [(&'static [u8], usize); 2] {
    // Payload coordinates from the captured ClientHello field layouts.
    [
        (include_bytes!("../../../ripdpi-packets/tests/fixtures/curl_tls12_example_com.bin"), 105),
        (include_bytes!("../../../ripdpi-packets/tests/fixtures/curl_tls13_example_com.bin"), 89),
    ]
}

pub(super) fn assert_record_reassembly(input: &[u8], output: &[u8], first_len: usize, count: usize) {
    let mut lengths = Vec::new();
    let mut reassembled = Vec::new();
    let mut cursor = 0;
    while cursor < output.len() {
        let header = &output[cursor..cursor + 5];
        assert_eq!(&header[..3], &input[..3]);
        let len = usize::from(u16::from_be_bytes([header[3], header[4]]));
        assert!((1..=16_384).contains(&len));
        reassembled.extend_from_slice(&output[cursor + 5..cursor + 5 + len]);
        lengths.push(len);
        cursor += 5 + len;
    }
    assert_eq!(cursor, output.len());
    assert_eq!(lengths.len(), count);
    assert_eq!(lengths[0], first_len);
    assert_eq!(reassembled, input[5..]);
    let handshake_len =
        (usize::from(reassembled[1]) << 16) | (usize::from(reassembled[2]) << 8) | usize::from(reassembled[3]);
    assert_eq!(handshake_len + 4, reassembled.len());
}

fn adaptive_prelude_plan(kind: TcpChainStepKind, payload: &[u8], boundary: usize) -> DesyncPlan {
    let mut group = DesyncGroup::new(0);
    let step = TcpChainStep::new(kind, OffsetExpr::adaptive(OffsetBase::AutoSniExt));
    group.actions.tcp_chain = vec![if kind == TcpChainStepKind::TlsRandRec {
        step.with_tls_randrec_payload(TcpTlsRandRecPayload {
            fragment_count: 4,
            min_fragment_size: 16,
            max_fragment_size: 64,
        })
    } else {
        step
    }];
    let context = ActivationContext {
        tcp_segment_hint: Some(TcpSegmentHint {
            snd_mss: Some(boundary as i64),
            advmss: None,
            pmtu: None,
            ip_header_overhead: 40,
        }),
        ..tcp_context(payload)
    };
    plan_tcp(&group, payload, 7, 64, context).expect("adaptive prelude plan")
}

#[test]
fn tlsrec_adaptive_candidate_uses_payload_coordinate_once() {
    for (payload, boundary) in captured_hellos() {
        let plan = adaptive_prelude_plan(TcpChainStepKind::TlsRec, payload, boundary);
        assert_eq!(plan.tls_prelude.applied_count, 1);
        assert_eq!(plan.tls_prelude.resolved_offset, Some(boundary));
        assert_record_reassembly(payload, &plan.tampered, boundary, 2);
    }
}

#[test]
fn tlsrandrec_adaptive_candidate_uses_payload_coordinate_once() {
    for (payload, boundary) in captured_hellos() {
        let plan = adaptive_prelude_plan(TcpChainStepKind::TlsRandRec, payload, boundary);
        assert_eq!(plan.tls_prelude.applied_count, 1);
        assert_eq!(plan.tls_prelude.resolved_offset, Some(boundary));
        assert_record_reassembly(payload, &plan.tampered, boundary, 5);
    }
}

#[test]
fn adaptive_tls_fallback_is_already_in_payload_coordinates() {
    let payload = [0u8; 128];
    let mut info = ProtoInfo::default();
    let result = crate::offset::resolve_adaptive_offset(
        OffsetExpr::adaptive(OffsetBase::AutoBalanced),
        &payload,
        payload.len(),
        0,
        &mut info,
        tcp_context(&payload),
        None,
        5,
    );
    assert_eq!(result, Some(64));
}
