#![cfg(feature = "lua-strategies")]

use std::collections::HashMap;

use ripdpi_strategy_lua::LuaStrategyEngine;
use ripdpi_strategy_trait::{
    Capabilities, ConnectionState, DesyncAction, DesyncPlan, Dissect, FlowDirection, FlowId, MarkerName,
    StrategyContext,
};

#[test]
fn lua_api_exposes_marker_table_and_pos_lookup() {
    let engine = LuaStrategyEngine::new().expect("lua vm");
    engine
        .load_bytes(
            "pos",
            br#"
            function marker(desync)
                return tostring(desync.dis.pos.sni_ext) .. ":" .. tostring(desync.pos("sni_ext"))
            end
            "#,
        )
        .expect("load script");
    engine.register_function("marker").expect("register marker");
    let strategy = engine.make_strategy("marker").expect("make strategy");
    let mut markers = HashMap::new();
    markers.insert(MarkerName::SniExt, 17);
    let dissect = Dissect { markers, ..Dissect::default() };
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &ConnectionState::default(),
        caps: &Capabilities::default(),
        flow_id: FlowId(1),
        payload: b"",
        direction: FlowDirection::Outbound,
    };
    let mut plan = DesyncPlan::default();

    strategy.plan(&ctx, &mut plan).expect("call marker");

    assert_eq!(plan.actions, vec![DesyncAction::Write(b"17:17".to_vec())]);
}
