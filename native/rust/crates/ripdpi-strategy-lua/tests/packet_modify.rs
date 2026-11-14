#![cfg(feature = "lua-strategies")]
use ripdpi_strategy_lua::LuaStrategyEngine;
use ripdpi_strategy_trait::{
    Capabilities, ConnectionState, DesyncAction, DesyncPlan, Dissect, FlowDirection, FlowId, StrategyContext,
    StrategyVerdict,
};
fn packet_plan(script: &str, udp: bool) -> Result<DesyncPlan, ripdpi_strategy_trait::StrategyError> {
    let engine = LuaStrategyEngine::new().unwrap();
    engine.load_bytes_registering_globals("packet-modify", script.as_bytes()).unwrap();
    let strategy = engine.make_strategy("candidate").unwrap();
    let dissect = Dissect { is_udp: Some(udp), ..Dissect::default() };
    let conn = ConnectionState::default();
    let caps = Capabilities::default();
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &conn,
        caps: &caps,
        flow_id: FlowId(1),
        payload: b"abcd",
        direction: FlowDirection::Outbound,
    };
    let mut plan = DesyncPlan::default();
    strategy.plan(&ctx, &mut plan)?;
    Ok(plan)
}
#[test]
fn packet_modify_materializes_payload_for_both_transports() {
    for udp in [false, true] {
        let plan = packet_plan("function candidate(d) d.dis.payload='WXYZ'; return VERDICT_MODIFY end", udp).unwrap();
        assert_eq!(plan.verdict, StrategyVerdict::Apply);
        assert_eq!(plan.actions, vec![DesyncAction::Write(b"WXYZ".to_vec())]);
    }
}
#[test]
fn packet_pass_and_drop_do_not_emit_modified_payload() {
    for verdict in ["VERDICT_PASS", "VERDICT_DROP"] {
        let script = format!("function candidate(d) d.dis.payload='WXYZ'; return {verdict} end");
        assert!(packet_plan(&script, true).unwrap().actions.is_empty());
    }
}
#[test]
fn packet_modify_rejects_header_changes_and_tcp_length_changes() {
    for script in [
        "function candidate(d) d.dis.tcp.th_flags=0; return VERDICT_MODIFY end",
        "function candidate(d) d.dis.payload='longer'; return VERDICT_MODIFY end",
    ] {
        assert!(packet_plan(script, false).is_err());
    }
    let plan = packet_plan("function candidate(d) d.dis.payload='longer'; return VERDICT_MODIFY end", true).unwrap();
    assert_eq!(plan.actions, vec![DesyncAction::Write(b"longer".to_vec())]);
}
#[test]
fn packet_modify_keeps_explicit_actions_without_extra_original() {
    let plan = packet_plan("function candidate(d) return d.split(2,false) end", false).unwrap();
    assert_eq!(plan.actions, vec![DesyncAction::Split { offset: 2, disorder: false }]);
}

#[test]
fn packet_modify_applies_ttl_before_changed_payload() {
    let plan =
        packet_plan("function candidate(d) d.dis.payload='WXYZ'; d.set_ttl(5); return VERDICT_MODIFY end", false)
            .unwrap();
    assert_eq!(plan.actions, vec![DesyncAction::SetTtl(5), DesyncAction::Write(b"WXYZ".to_vec())]);
    assert!(packet_plan("function candidate(d) d.dis.payload='WXYZ'; return d.split(2,false) end", false,).is_err());
}
