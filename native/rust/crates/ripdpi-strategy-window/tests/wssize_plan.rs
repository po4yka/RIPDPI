use ripdpi_strategy_trait::{
    Capabilities, CapabilityTier, ConnectionState, DesyncAction, DesyncPlan, DesyncStrategy, Dissect, FlowDirection,
    FlowId, RuntimeCapability, StrategyContext,
};
use ripdpi_strategy_window::WssizeStrategy;

#[test]
fn wssize_plans_scaled_tcp_window_clamp_action() {
    let dissect = Dissect::default();
    let conn = ConnectionState::default();
    let caps = Capabilities { tier: CapabilityTier::Tier1, available: vec![RuntimeCapability::TcpWindowClamp] };
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &conn,
        caps: &caps,
        flow_id: FlowId(1),
        payload: &[],
        direction: FlowDirection::Outbound,
    };
    let mut plan = DesyncPlan::default();

    WssizeStrategy::new(64, 2).plan(&ctx, &mut plan).expect("plan wssize");

    assert_eq!(plan.actions, vec![DesyncAction::SetWindowClamp(256)]);
}
