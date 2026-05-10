mod common;

use common::StubStrategy;
use ripdpi_strategy_registry::{OnFail, StrategyRegistry};
use ripdpi_strategy_trait::{
    Capabilities, ConnectionState, DesyncAction, DesyncPlan, Dissect, FlowDirection, FlowId, StrategyContext,
    StrategyVerdict,
};

#[test]
fn next_policy_runs_second_matching_strategy_after_error() {
    let mut registry = StrategyRegistry::new();
    registry.register_with_policy(Box::new(StubStrategy::failure("broken")), OnFail::Next);
    registry.register(Box::new(StubStrategy::success("split", DesyncAction::Split { offset: 3, disorder: false })));

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

    assert_eq!(registry.execute(&ctx, &mut plan), StrategyVerdict::Apply);
    assert_eq!(plan.actions, [DesyncAction::Split { offset: 3, disorder: false }]);
}
