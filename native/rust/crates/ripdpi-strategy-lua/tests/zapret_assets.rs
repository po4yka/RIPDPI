#![cfg(feature = "lua-strategies")]

use std::path::PathBuf;

use ripdpi_strategy_lua::LuaStrategyEngine;

fn lua_asset(name: &str) -> PathBuf {
    PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../../../../app/src/main/assets/lua").join(name)
}

#[test]
fn bundled_zapret2_scripts_load_unmodified() {
    let engine = LuaStrategyEngine::new().expect("Lua VM should initialize");

    engine.load_script(lua_asset("zapret-lib.lua")).expect("zapret lib should load");
    let functions = engine
        .load_script_registering_globals(lua_asset("zapret-antidpi.lua"))
        .expect("zapret antidpi script should load");

    assert!(functions.iter().any(|name| name == "multisplit"));
    assert!(functions.iter().any(|name| name == "synack_split"));
}
