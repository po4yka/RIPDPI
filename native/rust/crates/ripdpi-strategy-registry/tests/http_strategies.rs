use ripdpi_strategy_registry::StrategyRegistry;

#[test]
fn http_strategies_are_preloaded_in_builtin_registry() {
    let registry = StrategyRegistry::with_builtin_techniques();

    for id in ["http_domcase", "http_hostcase", "http_methodeol", "http_unixeol"] {
        let descriptor = registry.get(id).expect("registered HTTP strategy");
        assert!(descriptor.required_capabilities.is_empty());
    }
}
