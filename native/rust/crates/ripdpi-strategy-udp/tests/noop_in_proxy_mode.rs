use ripdpi_strategy_trait::{
    Capabilities, CapabilityTier, ConnectionState, DesyncPlan, DesyncStrategy, Dissect, FlowDirection, FlowId,
    L7Protocol, QuicDissect, StrategyContext, StrategyPlanOutcome,
};
use ripdpi_strategy_udp::UdpLenStrategy;

#[test]
fn udplen_skips_when_vpn_mode_is_unavailable() {
    let dissect = Dissect {
        proto: L7Protocol::Quic(QuicDissect { version: Some(1) }),
        src_port: 50000,
        dst_port: 443,
        ..Dissect::default()
    };
    let conn = ConnectionState::default();
    let caps = Capabilities { tier: CapabilityTier::Tier0, available: Vec::new() };
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &conn,
        caps: &caps,
        flow_id: FlowId(1),
        payload: &[],
        direction: FlowDirection::Outbound,
    };
    let mut plan = DesyncPlan::default();

    assert_eq!(UdpLenStrategy::new(4).plan(&ctx, &mut plan), Ok(StrategyPlanOutcome::Skipped));

    assert!(plan.actions.is_empty());
}

#[test]
fn udplen_skips_invalid_packet_with_vpn_mode() {
    let dissect = Dissect { proto: L7Protocol::Quic(QuicDissect::default()), src_port: 443, ..Dissect::default() };
    let conn = ConnectionState::default();
    let caps = Capabilities { tier: CapabilityTier::Tier3, available: Vec::new() };
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &conn,
        caps: &caps,
        flow_id: FlowId(2),
        payload: b"invalid packet",
        direction: FlowDirection::Outbound,
    };
    let mut plan = DesyncPlan::default();

    assert_eq!(UdpLenStrategy::new(4).plan(&ctx, &mut plan), Ok(StrategyPlanOutcome::Skipped));
    assert!(plan.actions.is_empty());
}
