use ripdpi_strategy_config::{StepType, parse_yaml_str};
use ripdpi_strategy_registry::{OnFail, StrategyRegistry};
use ripdpi_strategy_trait::{
    Capabilities, CapabilityTier, ConnectionState, DesyncAction, DesyncPlan, Dissect, FlowDirection, FlowId,
    RuntimeCapability, StrategyContext, StrategyVerdict,
};

#[test]
fn window_strategies_are_registered_and_yaml_params_parse() {
    let registry = StrategyRegistry::with_builtin_techniques();
    for id in ["wsize", "wssize"] {
        let descriptor = registry.get(id).expect("registered window strategy");
        assert_eq!(descriptor.required_tier, CapabilityTier::Tier1);
        assert_eq!(descriptor.required_capabilities, [RuntimeCapability::TcpWindowClamp]);
    }

    let yaml = "version: 1\nstrategies:\n  - id: window\n    steps:\n      - type: wsize\n        value: 4\n      - type: wssize\n        size: 64\n        scale: 2\n";
    let config = parse_yaml_str(yaml, ".").expect("parse yaml");
    let steps = &config.strategies[0].steps;

    assert_eq!(steps[0].kind, StepType::Wsize);
    assert_eq!(steps[0].value, Some(4));
    assert_eq!(steps[1].kind, StepType::Wssize);
    assert_eq!(steps[1].size, Some(64));
    assert_eq!(steps[1].scale, Some(2));
}

#[test]
fn configured_window_values_reach_planner() {
    for (step, expected) in
        [("type: wsize\n        value: 37", 37), ("type: wssize\n        size: 13\n        scale: 3", 104)]
    {
        let yaml = format!("version: 1\nstrategies:\n  - id: window\n    steps:\n      - {step}\n");
        let config = parse_yaml_str(&yaml, ".").expect("parse yaml");
        let registry = StrategyRegistry::from_loaded_config(&config).expect("materialize");
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
        assert_eq!(registry.execute(&ctx, &mut plan), StrategyVerdict::Apply);
        assert_eq!(plan.actions, vec![DesyncAction::SetWindowClamp(expected)]);
    }
}

#[test]
fn unavailable_window_capability_continues_to_next_strategy() {
    let mut registry = StrategyRegistry::new();
    registry.register_builtin_technique_with_policy("wsize", OnFail::Drop).expect("register wsize");
    registry.register_builtin_technique("fake").expect("register fake");
    let dissect = Dissect::default();
    let conn = ConnectionState::default();
    let caps = Capabilities::default();
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &conn,
        caps: &caps,
        flow_id: FlowId(1),
        payload: &[],
        direction: FlowDirection::Outbound,
    };
    let mut plan = DesyncPlan::default();
    assert_eq!(registry.execute(&ctx, &mut plan), StrategyVerdict::Apply);
    assert!(matches!(plan.actions.as_slice(), [DesyncAction::WriteFake { .. }]));
}
