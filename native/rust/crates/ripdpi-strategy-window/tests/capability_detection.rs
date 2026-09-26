use ripdpi_strategy_trait::{
    Capabilities, CapabilityTier, ConnectionState, DesyncPlan, DesyncStrategy, Dissect, FlowDirection, FlowId,
    RuntimeCapability, StrategyContext,
};
use ripdpi_strategy_window::WsizeStrategy;

#[test]
fn window_strategy_reports_unavailable_tcp_window_clamp() {
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

    assert!(matches!(
        WsizeStrategy::new(4).plan(&ctx, &mut plan),
        Err(ripdpi_strategy_trait::StrategyError::CapabilityUnavailable(RuntimeCapability::TcpWindowClamp))
    ));

    assert!(plan.actions.is_empty());
    assert_eq!(WsizeStrategy::new(4).describe().required_capabilities, [RuntimeCapability::TcpWindowClamp]);
}
