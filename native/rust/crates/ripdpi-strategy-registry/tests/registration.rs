mod common;

use common::StubStrategy;
use ripdpi_strategy_registry::StrategyRegistry;
use ripdpi_strategy_trait::DesyncAction;

#[test]
fn registered_strategy_appears_in_descriptor_list() {
    let mut registry = StrategyRegistry::new();
    registry.register(Box::new(StubStrategy::success("split", DesyncAction::Split { offset: 4, disorder: false })));

    let ids = registry.list().map(|descriptor| descriptor.id.as_str()).collect::<Vec<_>>();
    assert_eq!(ids, ["split"]);
}
