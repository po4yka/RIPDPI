#![cfg(feature = "lua-strategies")]

use ripdpi_strategy_lua::LuaStrategyEngine;
use ripdpi_strategy_trait::{
    Capabilities, ConnectionState, DesyncAction, DesyncPlan, Dissect, FlowDirection, FlowId, StrategyContext,
    StrategyVerdict,
};

#[test]
fn make_strategy_returns_strategy_that_calls_lua_function() {
    let engine = LuaStrategyEngine::new().expect("lua vm");
    engine
        .load_bytes(
            "writer",
            br#"
            function write_payload(desync)
                return "rewritten"
            end
            "#,
        )
        .expect("load script");
    engine.register_function("write_payload").expect("register function");
    let strategy = engine.make_strategy("write_payload").expect("make strategy");
    let dissect = Dissect::default();
    let conn = ConnectionState::default();
    let caps = Capabilities::default();
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &conn,
        caps: &caps,
        flow_id: FlowId(1),
        payload: b"original",
        direction: FlowDirection::Outbound,
    };
    let mut plan = DesyncPlan::default();

    strategy.plan(&ctx, &mut plan).expect("call lua function");

    assert_eq!(plan.verdict, StrategyVerdict::Apply);
    assert_eq!(plan.actions, vec![DesyncAction::Write(b"rewritten".to_vec())]);
}

#[test]
fn failed_lua_call_runs_only_once() {
    let engine = LuaStrategyEngine::new().expect("lua vm");
    engine
        .load_bytes(
            "fails_once",
            br#"
            calls = 0
            function fails_once(desync)
                calls = calls + 1
                if calls == 1 then error("first call failed") end
                return VERDICT_MODIFY
            end
            function call_count() return calls end
            "#,
        )
        .expect("load script");
    engine.register_function("fails_once").expect("register function");
    let strategy = engine.make_strategy("fails_once").expect("make strategy");
    let dissect = Dissect::default();
    let conn = ConnectionState::default();
    let caps = Capabilities::default();
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &conn,
        caps: &caps,
        flow_id: FlowId(2),
        payload: b"",
        direction: FlowDirection::Outbound,
    };

    assert!(strategy.plan(&ctx, &mut DesyncPlan::default()).is_err());
    assert_eq!(engine.call_i64("call_count").expect("read count"), 1);
}
