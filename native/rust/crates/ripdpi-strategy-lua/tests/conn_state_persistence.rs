#![cfg(feature = "lua-strategies")]

use ripdpi_strategy_lua::LuaStrategyEngine;
use ripdpi_strategy_trait::{
    Capabilities, ConnectionState, DesyncPlan, Dissect, FlowDirection, FlowId, StrategyContext,
};

#[test]
fn lua_strategy_persists_per_flow_connection_state() {
    let engine = LuaStrategyEngine::new().expect("lua vm");
    engine
        .load_bytes(
            "counter",
            br#"
            function count(desync)
                desync.conn.count = (desync.conn.count or 0) + 1
                return desync.conn.count
            end
            "#,
        )
        .expect("load script");
    engine.register_function("count").expect("register count");
    let strategy = engine.make_strategy("count").expect("make strategy");
    let dissect = Dissect::default();
    let conn = ConnectionState::default();
    let caps = Capabilities::default();
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &conn,
        caps: &caps,
        flow_id: FlowId(9),
        payload: b"",
        direction: FlowDirection::Outbound,
    };
    let mut plan = DesyncPlan::default();

    strategy.plan(&ctx, &mut plan).expect("first call");
    strategy.plan(&ctx, &mut plan).expect("second call");

    assert_eq!(engine.connection_count(FlowId(9)).expect("read count"), Some(2));
    engine.close_connection(FlowId(9)).expect("close");
    assert_eq!(engine.connection_count(FlowId(9)).expect("read missing count"), None);
}
