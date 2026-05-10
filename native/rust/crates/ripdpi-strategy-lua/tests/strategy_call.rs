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
