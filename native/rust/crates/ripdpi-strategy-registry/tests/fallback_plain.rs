mod common;

use common::StubStrategy;
use ripdpi_strategy_registry::{OnFail, StrategyRegistry};
use ripdpi_strategy_trait::{
    Capabilities, ConnectionState, DesyncPlan, Dissect, FlowDirection, FlowId, StrategyContext, StrategyVerdict,
};

#[test]
fn fallback_plain_policy_clears_plan_and_returns_plain_verdict() {
    let mut registry = StrategyRegistry::new();
    registry.register_with_policy(Box::new(StubStrategy::failure("broken")), OnFail::FallbackPlain);

    let dissect = Dissect::default();
    let conn = ConnectionState::default();
    let caps = Capabilities::default();
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &conn,
        caps: &caps,
        flow_id: FlowId(7),
        payload: b"payload",
        direction: FlowDirection::Outbound,
    };
    let mut plan = DesyncPlan::default();

    assert_eq!(registry.execute(&ctx, &mut plan), StrategyVerdict::FallbackPlain);
    assert!(plan.actions.is_empty());
}
