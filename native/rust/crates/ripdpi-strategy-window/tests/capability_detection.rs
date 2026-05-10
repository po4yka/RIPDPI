use ripdpi_strategy_trait::{
    Capabilities, CapabilityTier, ConnectionState, DesyncPlan, DesyncStrategy, Dissect, FlowDirection, FlowId,
    RuntimeCapability, StrategyContext,
};
use ripdpi_strategy_window::WsizeStrategy;

#[test]
fn window_strategy_noops_when_tcp_window_clamp_is_unavailable() {
    let dissect = Dissect::default();
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

    WsizeStrategy::new(4).plan(&ctx, &mut plan).expect("no-op without capability");

    assert!(plan.actions.is_empty());
    assert_eq!(WsizeStrategy::new(4).describe().required_capabilities, [RuntimeCapability::TcpWindowClamp]);
}
