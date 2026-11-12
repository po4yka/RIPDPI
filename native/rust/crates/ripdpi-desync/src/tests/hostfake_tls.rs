use super::*;

mod oracle {
    include!(concat!(env!("CARGO_MANIFEST_DIR"), "/tests/support/hostfake_tls.rs"));
}

#[test]
fn hostfake_preserves_record_headers_inside_fragmented_sni() {
    for tls13 in [false, true] {
        for cuts in [&[][..], &[59][..], &[59, 65][..]] {
            let input = oracle::client_hello(tls13, cuts);
            let mut group = DesyncGroup::new(0);
            group.actions.ttl = Some(9);
            let step = TcpChainStep::new(TcpChainStepKind::HostFake, OffsetExpr::marker(OffsetBase::PayloadEnd, 0))
                .with_fake_host_template(Some("cover.example.net".to_string()));
            group.actions.tcp_chain.push(step.clone());
            let plan = plan_tcp(&group, &input, 23, 64, tcp_context(&input)).expect("hostfake TLS plan");
            let span = resolve_hostfake_span(&step, &plan.tampered, 0, plan.tampered.len(), 23).expect("SNI span");
            let mut ttl = 64;
            let mut genuine = Vec::new();
            let mut fakes = Vec::new();
            for action in &plan.actions {
                match action {
                    DesyncAction::SetTtl(value) => ttl = *value,
                    DesyncAction::RestoreDefaultTtl => ttl = 64,
                    DesyncAction::Write(bytes) if ttl == 9 => fakes.push(bytes),
                    DesyncAction::Write(bytes) => genuine.extend_from_slice(bytes),
                    _ => {}
                }
            }
            assert_eq!(genuine, input, "real payload must be sent once and unchanged");
            assert_eq!(fakes.len(), 2);
            for fake in fakes {
                let mut fake_view = plan.tampered.clone();
                fake_view[span.host_start..span.host_end].copy_from_slice(fake);
                oracle::assert_fake_tls(&input, &fake_view, b"cover.example.net");
            }
        }
    }
}

#[test]
fn hostfake_region_rejects_a_mismatched_or_truncated_hostname() {
    let input = oracle::client_hello(true, &[59, 65]);
    let step = TcpChainStep::new(TcpChainStepKind::HostFake, OffsetExpr::marker(OffsetBase::PayloadEnd, 0));
    let span = resolve_hostfake_span(&step, &input, 0, input.len(), 23).expect("SNI span");
    let shifted = HostFakeSpan { host_start: span.host_start + 1, ..span };

    assert!(build_hostfake_region_bytes(&input, shifted, None, 23, false).is_none());
    assert!(build_hostfake_region_bytes(&input[..span.host_end - 1], span, None, 23, false).is_none());
}
