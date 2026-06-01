use ripdpi_strategy_config::{StepType, parse_yaml_str};
use ripdpi_strategy_registry::StrategyRegistry;

#[test]
fn udplen_is_registered_and_yaml_resolves_delta() {
    let registry = StrategyRegistry::with_builtin_techniques();
    assert!(registry.get("udplen").is_some());

    let yaml = "version: 1\nstrategies:\n  - id: udp\n    steps:\n      - type: udplen\n        delta: 4\n";
    let config = parse_yaml_str(yaml, ".").expect("parse yaml");
    let step = &config.strategies[0].steps[0];

    assert_eq!(step.kind, StepType::Udplen);
    assert_eq!(step.kind.registry_id(), "udplen");
    assert_eq!(step.delta, Some(4));
}
