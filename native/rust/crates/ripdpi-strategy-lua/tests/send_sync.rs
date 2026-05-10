#![cfg(feature = "lua-strategies")]

use ripdpi_strategy_lua::LuaStrategyEngine;
use static_assertions::assert_impl_all;

assert_impl_all!(LuaStrategyEngine: Send, Sync);
