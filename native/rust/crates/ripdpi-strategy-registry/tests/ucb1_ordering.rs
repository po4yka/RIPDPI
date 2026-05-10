mod common;

use common::StubStrategy;
use ripdpi_desync::{AdaptivePlannerHints, AdaptiveTlsRandRecProfile};
use ripdpi_strategy_registry::StrategyRegistry;
use ripdpi_strategy_trait::DesyncAction;

#[test]
fn adaptive_hints_prefer_matching_registered_strategy_ids() {
    let mut registry = StrategyRegistry::new();
    registry.register(Box::new(StubStrategy::success("split", DesyncAction::Split { offset: 3, disorder: false })));
    registry
        .register(Box::new(StubStrategy::success("tlsrec_split", DesyncAction::Split { offset: 5, disorder: false })));

    let hints = AdaptivePlannerHints {
        tlsrandrec_profile: Some(AdaptiveTlsRandRecProfile::Tight),
        ..AdaptivePlannerHints::default()
    };

    let ordered = registry.suggest_strategy_chain(&hints);
    assert_eq!(ordered.first(), Some(&"tlsrec_split"));
    assert_eq!(ordered.len(), 2);
}
