use ripdpi_strategy_config::{StepType, parse_yaml_str};
use ripdpi_strategy_registry::StrategyRegistry;

#[test]
fn yaml_ext_type_param_resolves_ipv6_ext_strategy() {
    let yaml = r#"
version: 1
strategies:
  - id: ipv6
    steps:
      - type: ipv6Ext
        ext_type: destopts
"#;

    let config = parse_yaml_str(yaml, ".").expect("parse yaml");
    let registry = StrategyRegistry::with_builtin_techniques();
    let step = &config.strategies[0].steps[0];

    assert_eq!(step.kind, StepType::Ipv6Ext);
    assert_eq!(step.ext_type.as_deref(), Some("destopts"));
    assert_eq!(step.kind.registry_id(), "ipv6_ext");
    assert!(registry.get(step.kind.registry_id()).is_some());
}
