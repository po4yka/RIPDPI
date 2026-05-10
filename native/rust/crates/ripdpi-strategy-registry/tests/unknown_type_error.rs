use ripdpi_strategy_registry::StrategyRegistry;

#[test]
fn unknown_technique_type_does_not_resolve() {
    let mut registry = StrategyRegistry::with_builtin_techniques();
    assert!(registry.get("nonexistent_type").is_none());
    assert!(registry.register_builtin_technique("nonexistent_type").is_err());
}
