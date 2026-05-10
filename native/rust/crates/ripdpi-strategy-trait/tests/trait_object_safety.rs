use ripdpi_strategy_trait::{DesyncPlan, DesyncStrategy, StrategyContext, StrategyDescriptor, StrategyError};

#[derive(Debug)]
struct NoopStrategy;

impl DesyncStrategy for NoopStrategy {
    fn id(&self) -> &str {
        "noop"
    }

    fn matches(&self, _ctx: &StrategyContext<'_>) -> bool {
        true
    }

    fn plan(&self, _ctx: &StrategyContext<'_>, _plan: &mut DesyncPlan) -> Result<(), StrategyError> {
        Ok(())
    }

    fn describe(&self) -> StrategyDescriptor {
        StrategyDescriptor::default()
    }
}

#[test]
fn strategy_trait_is_object_safe() {
    let strategy: Box<dyn DesyncStrategy> = Box::new(NoopStrategy);
    assert_eq!(strategy.id(), "noop");
}
