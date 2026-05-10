use ripdpi_strategy_registry::StrategyRegistry;
use ripdpi_strategy_trait::{
    Capabilities, CapabilityTier, ConnectionState, DesyncPlan, Dissect, FlowDirection, FlowId, StrategyContext,
    StrategyVerdict,
};

#[test]
fn tier_two_technique_is_skipped_when_capability_tier_is_absent() {
    let mut registry = StrategyRegistry::new();
    registry.register_builtin_technique("disorder").expect("register disorder");
    let dissect = Dissect::default();
    let conn = ConnectionState::default();
    let caps = Capabilities { tier: CapabilityTier::Tier0, available: Vec::new() };
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &conn,
        caps: &caps,
        flow_id: FlowId(1),
        payload: b"payload",
        direction: FlowDirection::Outbound,
    };
    let mut plan = DesyncPlan::default();

    assert_eq!(registry.execute(&ctx, &mut plan), StrategyVerdict::FallbackPlain);
    assert!(registry.get("disorder").is_some());
    assert!(plan.actions.is_empty());
}
