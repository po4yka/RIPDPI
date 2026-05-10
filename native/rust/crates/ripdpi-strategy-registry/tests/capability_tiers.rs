use ripdpi_strategy_registry::StrategyRegistry;
use ripdpi_strategy_trait::{CapabilityTier, RuntimeCapability};

#[test]
fn built_in_techniques_report_required_tiers() {
    let registry = StrategyRegistry::with_builtin_techniques();
    assert_eq!(registry.get("split").expect("split").required_tier, CapabilityTier::Tier0);
    assert_eq!(registry.get("fake").expect("fake").required_tier, CapabilityTier::Tier0);

    for id in ["disorder", "multi_disorder", "seq_overlap"] {
        let descriptor = registry.get(id).unwrap_or_else(|| panic!("{id} descriptor"));
        assert_eq!(descriptor.required_tier, CapabilityTier::Tier2);
        assert_eq!(descriptor.required_capabilities, [RuntimeCapability::ReplacementSocket]);
    }
}
