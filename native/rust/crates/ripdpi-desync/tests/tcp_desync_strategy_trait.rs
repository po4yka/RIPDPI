use ripdpi_config::DesyncGroup;
use ripdpi_desync::{
    ActivationContext, ActivationTcpState, ActivationTransport, AdaptivePlannerHints, TcpDesyncStrategy,
};
use ripdpi_strategy_trait::{
    Capabilities, ConnectionState, DesyncStrategy, Dissect, FlowDirection, FlowId, StrategyContext,
};

fn activation_context(payload: &[u8]) -> ActivationContext {
    ActivationContext {
        round: 1,
        payload_size: payload.len() as i64,
        stream_start: 0,
        stream_end: payload.len().saturating_sub(1) as i64,
        seqovl_supported: false,
        transport: ActivationTransport::Tcp,
        tcp_segment_hint: None,
        tcp_state: ActivationTcpState::default(),
        resolved_fake_ttl: None,
        adaptive: AdaptivePlannerHints::default(),
    }
}

#[test]
fn tcp_desync_strategy_exposes_trait_metadata_and_match() {
    let payload = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
    let group = DesyncGroup::new(0);
    let strategy = TcpDesyncStrategy::new(&group, 7, 64, activation_context(payload));
    let dissect = Dissect::default();
    let conn = ConnectionState::default();
    let caps = Capabilities::default();
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &conn,
        caps: &caps,
        flow_id: FlowId(1),
        payload,
        direction: FlowDirection::Outbound,
    };

    assert_eq!(strategy.id(), "tcp_desync");
    assert!(strategy.matches(&ctx));
    let descriptor = strategy.describe();
    assert_eq!(descriptor.id, "tcp_desync");
    assert!(!descriptor.supported_protocols.is_empty());
}
