use ripdpi_strategy_config::{parse_yaml_str, StepType};
use ripdpi_strategy_registry::StrategyRegistry;
use ripdpi_strategy_trait::{CapabilityTier, RuntimeCapability};

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
