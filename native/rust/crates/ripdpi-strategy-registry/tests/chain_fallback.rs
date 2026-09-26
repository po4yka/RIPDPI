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

#[test]
fn skipped_ipv6_step_continues_even_with_drop_on_fail() {
    let mut registry = StrategyRegistry::new();
    registry.register_builtin_technique_with_policy("ipv6_ext", OnFail::Drop).expect("IPv6 step");
    registry.register_builtin_technique("split").expect("split step");

    let dissect = Dissect { is_ipv6: true, ..Dissect::default() };
    let conn = ConnectionState::default();
    let caps = Capabilities::default();
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &conn,
        caps: &caps,
        flow_id: FlowId(8),
        payload: b"not an IPv6 packet",
        direction: FlowDirection::Outbound,
    };
    let mut plan = DesyncPlan::default();

    assert_eq!(registry.execute(&ctx, &mut plan), StrategyVerdict::Apply);
    assert_eq!(plan.actions, [DesyncAction::Split { offset: 0, disorder: false }]);
}

#[test]
fn skipped_step_discards_partial_plan_before_next_step() {
    let mut registry = StrategyRegistry::new();
    registry.register(Box::new(StubStrategy::skipped("partial", Some(DesyncAction::SetTtl(3)))));
    registry.register(Box::new(StubStrategy::success("split", DesyncAction::Split { offset: 3, disorder: false })));

    let dissect = Dissect::default();
    let conn = ConnectionState::default();
    let caps = Capabilities::default();
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &conn,
        caps: &caps,
        flow_id: FlowId(9),
        payload: b"payload",
        direction: FlowDirection::Outbound,
    };
    let mut plan = DesyncPlan::default();

    assert_eq!(registry.execute(&ctx, &mut plan), StrategyVerdict::Apply);
    assert_eq!(plan.actions, [DesyncAction::Split { offset: 3, disorder: false }]);
}
