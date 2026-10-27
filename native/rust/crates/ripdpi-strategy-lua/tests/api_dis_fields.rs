#![cfg(feature = "lua-strategies")]

use ripdpi_strategy_lua::LuaStrategyEngine;
use ripdpi_strategy_trait::{
    Capabilities, ConnectionState, DesyncAction, DesyncPlan, Dissect, FlowDirection, FlowId, L7Protocol, QuicDissect,
    StrategyContext, TlsDissect,
};

#[test]
fn lua_api_exposes_dis_fields_and_detect() {
    let engine = LuaStrategyEngine::new().expect("lua vm");
    engine
        .load_bytes(
            "api",
            br#"
            function inspect(desync)
                if not desync.detect("tls") then return "bad-detect" end
                return desync.dis.proto .. ":" .. desync.dis.hostname .. ":" .. desync.dis.src_port .. ":" .. tostring(desync.dis.is_ipv6)
            end
            "#,
        )
        .expect("load script");
    engine.register_function("inspect").expect("register inspect");
    let strategy = engine.make_strategy("inspect").expect("make strategy");
    let dissect = Dissect {
        proto: L7Protocol::Tls(TlsDissect { sni: Some("example.com".to_owned()), is_client_hello: true }),
        src_port: 44321,
        dst_port: 443,
        is_ipv6: true,
        ..Dissect::default()
    };
    let conn = ConnectionState::default();
    let caps = Capabilities::default();
    let ctx = context(&dissect, &conn, &caps);
    let mut plan = DesyncPlan::default();

    strategy.plan(&ctx, &mut plan).expect("call inspect");

    assert_eq!(plan.actions, vec![DesyncAction::Write(b"tls:example.com:44321:true".to_vec())]);
}

#[test]
fn lua_dis_presents_udp_only_protocols_as_udp() {
    let engine = LuaStrategyEngine::new().expect("lua vm");
    engine
        .load_bytes(
            "udp_api",
            br#"
            function transport(desync)
                if desync.dis.tcp then return "tcp" end
                local udp = desync.dis.udp
                return "udp:" .. udp.uh_sport .. ":" .. udp.uh_dport .. ":" .. udp.uh_ulen
            end
            "#,
        )
        .expect("load script");
    engine.register_function("transport").expect("register transport");
    let strategy = engine.make_strategy("transport").expect("make strategy");
    let dissect = Dissect {
        proto: L7Protocol::Quic(QuicDissect::default()),
        src_port: 50000,
        dst_port: 443,
        ..Dissect::default()
    };
    let conn = ConnectionState::default();
    let caps = Capabilities::default();
    let mut ctx = context(&dissect, &conn, &caps);
    ctx.payload = b"initial";
    let mut plan = DesyncPlan::default();

    strategy.plan(&ctx, &mut plan).expect("call transport");

    assert_eq!(plan.actions, vec![DesyncAction::Write(b"udp:50000:443:15".to_vec())]);
}

fn context<'a>(dissect: &'a Dissect, conn: &'a ConnectionState, caps: &'a Capabilities) -> StrategyContext<'a> {
    StrategyContext { dissect, conn, caps, flow_id: FlowId(1), payload: b"", direction: FlowDirection::Outbound }
}
