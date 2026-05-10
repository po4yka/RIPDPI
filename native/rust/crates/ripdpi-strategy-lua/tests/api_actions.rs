#![cfg(feature = "lua-strategies")]

use ripdpi_strategy_lua::LuaStrategyEngine;
use ripdpi_strategy_trait::{
    Capabilities, CapabilityTier, ConnectionState, DesyncAction, DesyncPlan, Dissect, FlowDirection, FlowId,
    RuntimeCapability, StrategyContext, StrategyVerdict,
};

#[test]
fn lua_api_action_functions_append_plan_actions() {
    let engine = LuaStrategyEngine::new().expect("lua vm");
    engine
        .load_bytes(
            "actions",
            br#"
            function actions(desync)
                desync.set_ttl(5)
                desync.split(3, true, 5)
                desync.rawsend("abc")
                desync.wsize(64)
                return VERDICT_MODIFY
            end
            "#,
        )
        .expect("load script");
    engine.register_function("actions").expect("register actions");
    let strategy = engine.make_strategy("actions").expect("make strategy");
    let dissect = Dissect::default();
    let caps = Capabilities {
        tier: CapabilityTier::Tier3,
        available: vec![RuntimeCapability::VpnMode, RuntimeCapability::TcpWindowClamp],
    };
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &ConnectionState::default(),
        caps: &caps,
        flow_id: FlowId(1),
        payload: b"",
        direction: FlowDirection::Outbound,
    };
    let mut plan = DesyncPlan::default();

    strategy.plan(&ctx, &mut plan).expect("call actions");

    assert_eq!(plan.verdict, StrategyVerdict::Apply);
    assert_eq!(
        plan.actions,
        vec![
            DesyncAction::SetTtl(5),
            DesyncAction::Split { offset: 3, disorder: true },
            DesyncAction::RawSend(b"abc".to_vec()),
            DesyncAction::SetWindowClamp(64),
        ]
    );
}

#[test]
fn lua_drop_sets_drop_verdict() {
    let engine = LuaStrategyEngine::new().expect("lua vm");
    engine.load_bytes("dropper", b"function dropper(desync) return desync.drop() end").expect("load script");
    engine.register_function("dropper").expect("register dropper");
    let strategy = engine.make_strategy("dropper").expect("make strategy");
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

    strategy.plan(&ctx, &mut plan).expect("call dropper");

    assert_eq!(plan.verdict, StrategyVerdict::Drop);
}

#[test]
fn lua_zapret_compat_actions_append_typed_plan_actions() {
    let engine = LuaStrategyEngine::new().expect("lua vm");
    engine
        .load_bytes(
            "typed_actions",
            br#"
            function typed_actions(desync)
                desync.fake(7, "rand", "/tmp/fake.bin")
                desync.oob(2, 33)
                desync.fake_rst(5)
                desync.udplen(4)
                return VERDICT_MODIFY
            end
            "#,
        )
        .expect("load script");
    engine.register_function("typed_actions").expect("register typed actions");
    let strategy = engine.make_strategy("typed_actions").expect("make strategy");
    let dissect = Dissect::default();
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &ConnectionState::default(),
        caps: &Capabilities::default(),
        flow_id: FlowId(2),
        payload: b"payload",
        direction: FlowDirection::Outbound,
    };
    let mut plan = DesyncPlan::default();

    strategy.plan(&ctx, &mut plan).expect("call typed actions");

    assert_eq!(plan.verdict, StrategyVerdict::Apply);
    assert_eq!(
        plan.actions,
        vec![
            DesyncAction::WriteFake {
                ttl: Some(7),
                sni_mode: Some("rand".to_owned()),
                payload_file: Some("/tmp/fake.bin".to_owned()),
            },
            DesyncAction::WriteUrgent { prefix: b"pa".to_vec(), urgent_byte: 33 },
            DesyncAction::SendFakeRst { ttl: Some(5) },
            DesyncAction::UdpLen { delta: 4 },
        ]
    );
}
