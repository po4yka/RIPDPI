#![cfg(feature = "lua-strategies")]

use ripdpi_strategy_lua::LuaStrategyEngine;

#[test]
fn load_bytes_executes_valid_lua() {
    let engine = LuaStrategyEngine::new().expect("lua vm");

    engine.load_bytes("test", b"function hello() return 42 end").expect("load script");

    assert_eq!(engine.call_i64("hello").expect("call hello"), 42);
}
