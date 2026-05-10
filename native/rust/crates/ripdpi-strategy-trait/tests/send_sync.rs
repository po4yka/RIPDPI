use ripdpi_strategy_trait::DesyncStrategy;
use static_assertions::assert_impl_all;

assert_impl_all!(dyn DesyncStrategy: Send, Sync);
