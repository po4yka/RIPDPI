#![cfg(feature = "lua-strategies")]

use ripdpi_strategy_lua::LuaStrategyEngine;
use ripdpi_strategy_trait::{
    Capabilities, ConnectionState, DesyncPlan, Dissect, FlowDirection, FlowId, StrategyContext, StrategyError,
};

#[test]
fn lua_api_wrong_arg_type_returns_lua_type_error() {
    let engine = LuaStrategyEngine::new().expect("lua vm");
    engine.load_bytes("bad", b"function bad(desync) return desync.split('sni', true, 5) end").expect("load script");
    engine.register_function("bad").expect("register bad");
    let strategy = engine.make_strategy("bad").expect("make strategy");
    let dissect = Dissect::default();
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &ConnectionState::default(),
        caps: &Capabilities::default(),
        flow_id: FlowId(1),
        payload: b"",
        direction: FlowDirection::Outbound,
    };
    let mut plan = DesyncPlan::default();

    let error = strategy.plan(&ctx, &mut plan).expect_err("type error");

    assert!(matches!(error, StrategyError::LuaTypeError(_)));
}
