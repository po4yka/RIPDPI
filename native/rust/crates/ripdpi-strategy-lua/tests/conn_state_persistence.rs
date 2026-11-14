#![cfg(feature = "lua-strategies")]

use ripdpi_strategy_lua::LuaStrategyEngine;
use ripdpi_strategy_trait::{
    Capabilities, ConnectionState, DesyncAction, DesyncPlan, Dissect, FlowDirection, FlowId, StrategyContext,
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
    strategy.plan(&ctx, &mut DesyncPlan::default()).expect("reopened flow");
    assert_eq!(engine.connection_count(FlowId(9)).expect("read reopened count"), Some(1));
}

#[test]
fn lua_strategy_keeps_admitted_state_and_bypasses_new_flows_at_capacity() {
    let engine = LuaStrategyEngine::new().expect("lua vm");
    engine
        .load_bytes("counter", b"function count(desync) desync.conn.count = (desync.conn.count or 0) + 1 end")
        .expect("load script");
    engine.register_function("count").expect("register count");
    let strategy = engine.make_strategy("count").expect("make strategy");
    let dissect = Dissect::default();
    let conn = ConnectionState::default();
    let caps = Capabilities::default();
    let mut plan = DesyncPlan::default();
    let mut call = |flow_id| {
        let ctx = StrategyContext {
            dissect: &dissect,
            conn: &conn,
            caps: &caps,
            flow_id: FlowId(flow_id),
            payload: b"",
            direction: FlowDirection::Outbound,
        };
        strategy.plan(&ctx, &mut plan)
    };

    for flow_id in 0..1024 {
        call(flow_id).expect("admit flow");
    }
    call(0).expect("existing flow must remain usable");
    call(1024).expect("capacity overflow passes through");

    assert_eq!(engine.connection_count(FlowId(0)).expect("read count"), Some(2));
    assert_eq!(engine.connection_count(FlowId(1)).expect("read count"), Some(1), "admitted state is not evicted");
    assert_eq!(engine.connection_count(FlowId(2)).expect("read count"), Some(1));
    assert_eq!(engine.connection_count(FlowId(1024)).expect("read bypassed count"), None);
}

#[test]
fn lua_strategy_latches_bypass_and_preserves_live_state_after_flow_churn() {
    let engine = LuaStrategyEngine::new().expect("lua vm");
    engine
        .load_bytes(
            "first_packet",
            br#"
            function first_packet(desync)
                desync.conn.count = (desync.conn.count or 0) + 1
                if desync.conn.count == 1 then return "first-packet-burst" end
                return "continued"
            end
            "#,
        )
        .expect("load script");
    engine.register_function("first_packet").expect("register function");
    let strategy = engine.make_strategy("first_packet").expect("make strategy");
    let dissect = Dissect::default();
    let conn = ConnectionState::default();
    let caps = Capabilities::default();
    let call = |flow_id| {
        let ctx = StrategyContext {
            dissect: &dissect,
            conn: &conn,
            caps: &caps,
            flow_id: FlowId(flow_id),
            payload: b"payload",
            direction: FlowDirection::Outbound,
        };
        let mut plan = DesyncPlan::default();
        strategy.plan(&ctx, &mut plan).expect("plan");
        plan.actions
    };

    for flow_id in 0..1024 {
        assert_eq!(call(flow_id), vec![DesyncAction::Write(b"first-packet-burst".to_vec())]);
    }
    for flow_id in 1024..4097 {
        assert!(call(flow_id).is_empty(), "an unknown flow passes through after capacity overflow");
        assert!(!engine.has_connection(FlowId(flow_id)).expect("has connection"));
    }
    assert_eq!(call(0), vec![DesyncAction::Write(b"continued".to_vec())]);
    assert_eq!(engine.connection_count(FlowId(0)).expect("read live count"), Some(2));

    engine.close_connection(FlowId(1)).expect("close admitted flow");
    assert!(call(1024).is_empty(), "a bypassed flow must not start its script when a slot becomes free");
    assert!(call(1).is_empty(), "admission remains closed until engine replacement");
    assert_eq!(call(0), vec![DesyncAction::Write(b"continued".to_vec())]);
    assert_eq!(engine.connection_count(FlowId(0)).expect("read live count"), Some(3));
}
