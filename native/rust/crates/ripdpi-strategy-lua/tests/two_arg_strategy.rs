#![cfg(feature = "lua-strategies")]

use ripdpi_strategy_lua::LuaStrategyEngine;
use ripdpi_strategy_trait::{
    Capabilities, ConnectionState, DesyncAction, DesyncPlan, Dissect, FlowDirection, FlowId, StrategyContext,
    StrategyVerdict,
};

#[test]
fn two_arg_lua_strategy_receives_nil_ctx_and_desync_table() {
    let engine = LuaStrategyEngine::new().expect("Lua VM should initialize");
    engine
        .load_bytes_registering_globals(
            "two_arg.lua",
            br#"
                function candidate(ctx, desync)
                    if ctx ~= nil then error("ctx should be nil in RIPDPI shim") end
                    desync.rawsend("ok")
                    return VERDICT_MODIFY
                end
            "#,
        )
        .expect("script should load");

    let strategy = engine.make_strategy("candidate").expect("strategy should be registered");
    let dissect = Dissect::default();
    let conn = ConnectionState::default();
    let caps = Capabilities::default();
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &conn,
        caps: &caps,
        flow_id: FlowId(42),
        payload: b"payload",
        direction: FlowDirection::Outbound,
    };
    let mut plan = DesyncPlan::default();

    strategy.plan(&ctx, &mut plan).expect("strategy should execute");

    assert_eq!(plan.verdict, StrategyVerdict::Apply);
    assert_eq!(plan.actions, vec![DesyncAction::RawSend(b"ok".to_vec())]);
}
