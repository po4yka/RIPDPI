use ripdpi_strategy_registry::StrategyRegistry;
use static_assertions::assert_impl_all;

assert_impl_all!(StrategyRegistry: Send, Sync);
