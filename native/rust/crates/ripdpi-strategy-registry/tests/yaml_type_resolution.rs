use ripdpi_strategy_config::parse_yaml_str;
use ripdpi_strategy_registry::StrategyRegistry;

#[test]
fn yaml_step_type_resolves_to_registered_technique() {
    let yaml = "version: 1\nstrategies:\n  - id: fake_chain\n    steps:\n      - type: fake\n";
    let config = parse_yaml_str(yaml, ".").expect("parse yaml");
    let registry = StrategyRegistry::with_builtin_techniques();
    let step = &config.strategies[0].steps[0];

    assert_eq!(step.kind.registry_id(), "fake");
    assert!(registry.get(step.kind.registry_id()).is_some());
}

#[test]
fn synack_yaml_step_types_resolve_to_registered_techniques() {
    let yaml = "version: 1\nstrategies:\n  - id: synack_chain\n    steps:\n      - type: synack\n        ttl: 5\n      - type: synack_split\n";
    let config = parse_yaml_str(yaml, ".").expect("parse yaml");
    let registry = StrategyRegistry::with_builtin_techniques();

    for step in &config.strategies[0].steps {
        assert!(registry.get(step.kind.registry_id()).is_some(), "{} did not resolve", step.kind.registry_id());
    }
}
