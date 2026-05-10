#![cfg(feature = "lua-strategies")]

use ripdpi_strategy_lua::LuaStrategyEngine;

#[test]
fn lua_vm_initializes() {
    assert!(LuaStrategyEngine::new().is_ok());
}
