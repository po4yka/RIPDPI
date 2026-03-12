use ciadpi_config::{DesyncGroup, OffsetBase, OffsetExpr, TcpChainStep, TcpChainStepKind};
use ciadpi_desync::{build_fake_packet, plan_tcp, ActivationContext, ActivationTransport, PlannedStep};
use ciadpi_packets::{http_marker_info, second_level_domain_span, tls_marker_info, DEFAULT_FAKE_TLS};

fn tcp_context(payload: &[u8]) -> ActivationContext {
    ActivationContext {
        round: 1,
        payload_size: payload.len() as i64,
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1) as i64,
        transport: ActivationTransport::Tcp,
    }
}

#[test]
fn plan_tcp_http_chain_resolves_host_and_endsld_markers() {
    let payload = b"GET / HTTP/1.1\r\nHost: foo.example.co.uk\r\n\r\n";
    let markers = http_marker_info(payload).expect("http markers");
    let (_, sld_end) =
        second_level_domain_span(&payload[markers.host_start..markers.host_end]).expect("structural sld span");

    let mut group = DesyncGroup::new(0);
    group.tcp_chain = vec![
        TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::marker(OffsetBase::Host, 0)),
        TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::marker(OffsetBase::EndSld, 0)),
    ];

    let plan = plan_tcp(&group, payload, 7, 64, tcp_context(payload)).expect("plan http marker chain");

    assert_eq!(
        plan.steps,
        vec![
            PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: markers.host_start as i64 },
            PlannedStep {
                kind: TcpChainStepKind::Split,
                start: markers.host_start as i64,
                end: (markers.host_start + sld_end) as i64,
            },
        ]
    );
}

#[test]
fn plan_tcp_tls_chain_resolves_sniext_and_tls_endhost_markers() {
    let markers = tls_marker_info(DEFAULT_FAKE_TLS).expect("tls markers");

    let mut group = DesyncGroup::new(0);
    group.tcp_chain = vec![
        TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::marker(OffsetBase::SniExt, 0)),
        TcpChainStep::new(TcpChainStepKind::Split, OffsetExpr::tls_marker(OffsetBase::EndHost, 0)),
    ];

    let plan =
        plan_tcp(&group, DEFAULT_FAKE_TLS, 7, 64, tcp_context(DEFAULT_FAKE_TLS)).expect("plan tls marker chain");

    assert_eq!(
        plan.steps,
        vec![
            PlannedStep { kind: TcpChainStepKind::Split, start: 0, end: markers.sni_ext_start as i64 },
            PlannedStep { kind: TcpChainStepKind::Split, start: markers.sni_ext_start as i64, end: markers.host_end as i64 },
        ]
    );
}

#[test]
fn build_fake_packet_resolves_tls_only_fake_offset_marker() {
    let markers = tls_marker_info(DEFAULT_FAKE_TLS).expect("tls markers");
    let mut group = DesyncGroup::new(0);
    group.fake_offset = Some(OffsetExpr::tls_marker(OffsetBase::EndHost, 0));

    let fake = build_fake_packet(&group, DEFAULT_FAKE_TLS, 7).expect("build fake packet");

    assert_eq!(fake.fake_offset, markers.host_end);
}
