use ripdpi_strategy_trait::{
    Capabilities, ConnectionState, DesyncStrategy, Dissect, FlowDirection, FlowId, L7Protocol, QuicDissect,
    StrategyContext, TlsDissect,
};
use ripdpi_strategy_udp::UdpLenStrategy;

#[test]
fn udplen_matches_quic_or_unknown_udp_ports() {
    let conn = ConnectionState::default();
    let caps = Capabilities::default();
    let quic = Dissect {
        proto: L7Protocol::Quic(QuicDissect { version: Some(1) }),
        src_port: 50000,
        dst_port: 443,
        ..Dissect::default()
    };
    let tls = Dissect {
        proto: L7Protocol::Tls(TlsDissect { sni: Some("example.com".to_owned()), is_client_hello: true }),
        src_port: 50000,
        dst_port: 443,
        ..Dissect::default()
    };

    assert!(UdpLenStrategy::new(4).matches(&context(&quic, &conn, &caps)));
    assert!(!UdpLenStrategy::new(4).matches(&context(&tls, &conn, &caps)));
}

fn context<'a>(dissect: &'a Dissect, conn: &'a ConnectionState, caps: &'a Capabilities) -> StrategyContext<'a> {
    StrategyContext { dissect, conn, caps, flow_id: FlowId(1), payload: &[], direction: FlowDirection::Outbound }
}
