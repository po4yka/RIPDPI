use ripdpi_strategy_config::parse_yaml_str;
use ripdpi_strategy_registry::{StrategyRegistry, StrategyRegistryError};

#[test]
fn failed_config_registration_preserves_existing_entries() {
    let config = parse_yaml_str(
        "version: 1\nstrategies:\n  - id: partial\n    steps:\n      - type: wsize\n      - type: unknown_strategy\n",
        ".",
    )
    .expect("parse yaml");
    let mut registry = StrategyRegistry::new();
    registry.register_builtin_technique("fake").expect("register existing strategy");

    assert!(matches!(
        registry.register_loaded_config(&config),
        Err(StrategyRegistryError::UnknownType(id)) if id == "unknown_strategy"
    ));
    assert_eq!(registry.list().map(|descriptor| descriptor.id.as_str()).collect::<Vec<_>>(), ["fake"]);
}
