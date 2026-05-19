#![cfg(feature = "lua-strategies")]

use std::path::PathBuf;

use ripdpi_strategy_lua::LuaStrategyEngine;
use ripdpi_strategy_trait::{
    Capabilities, ConnectionState, DesyncAction, DesyncPlan, Dissect, FlowDirection, FlowId, L7Protocol,
    StrategyContext, StrategyVerdict, TlsDissect,
};

fn lua_asset(name: &str) -> PathBuf {
    golden_test_support::repo_root().join("app/src/main/assets/lua").join(name)
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

#[test]
fn bundled_zapret2_multisplit_executes_through_compat_layer() {
    let engine = LuaStrategyEngine::new().expect("Lua VM should initialize");
    engine.load_script(lua_asset("zapret-lib.lua")).expect("zapret lib should load");
    engine.load_script_registering_globals(lua_asset("zapret-antidpi.lua")).expect("zapret antidpi script should load");

    let strategy = engine.make_strategy("multisplit").expect("multisplit strategy");
    let dissect = Dissect {
        proto: L7Protocol::Tls(TlsDissect { sni: Some("example.com".to_owned()), is_client_hello: true }),
        ..Dissect::default()
    };
    let conn = ConnectionState::default();
    let caps = Capabilities::default();
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &conn,
        caps: &caps,
        flow_id: FlowId(99),
        payload: b"payload",
        direction: FlowDirection::Outbound,
    };
    let mut plan = DesyncPlan::default();

    strategy.plan(&ctx, &mut plan).expect("multisplit should execute");

    assert_eq!(plan.verdict, StrategyVerdict::Drop);
    assert_eq!(plan.actions, vec![DesyncAction::RawSend(b"p".to_vec()), DesyncAction::RawSend(b"ayload".to_vec())]);
}
