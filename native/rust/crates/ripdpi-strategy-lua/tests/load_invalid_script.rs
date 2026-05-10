#![cfg(feature = "lua-strategies")]

use ripdpi_strategy_lua::{LuaError, LuaStrategyEngine};

#[test]
fn load_bytes_returns_script_load_error_for_invalid_lua() {
    let engine = LuaStrategyEngine::new().expect("lua vm");

    let error = engine.load_bytes("bad", b"this is not lua {{{").expect_err("invalid script");

    assert!(matches!(error, LuaError::ScriptLoad(_)));
}
