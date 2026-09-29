#![cfg(feature = "lua-strategies")]

use ripdpi_strategy_lua::LuaStrategyEngine;
use ripdpi_strategy_trait::{
    Capabilities, ConnectionState, DesyncAction, DesyncPlan, Dissect, FlowDirection, FlowId, HttpDissect, L7Protocol,
    MarkerName, StrategyContext, StrategyVerdict, TlsDissect,
};

const HTTP: &[u8] = b"GET / HTTP/1.1\r\nHost: example.com\r\nUser-Agent: abcdef\r\n\r\n";

fn engine() -> LuaStrategyEngine {
    let engine = LuaStrategyEngine::new().unwrap();
    for (name, bytes) in [
        (
            "zapret-lib.lua",
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/../../../../core/engine/src/main/assets/lua/zapret-lib.lua"
            ))
            .as_slice(),
        ),
        (
            "zapret-antidpi.lua",
            include_bytes!(concat!(
                env!("CARGO_MANIFEST_DIR"),
                "/../../../../core/engine/src/main/assets/lua/zapret-antidpi.lua"
            ))
            .as_slice(),
        ),
    ] {
        engine.load_bytes_registering_globals(name, bytes).unwrap();
    }
    engine
}

fn plan(
    engine: &LuaStrategyEngine,
    function: &str,
    payload: &[u8],
    dissect: &Dissect,
    direction: FlowDirection,
    flow: u64,
) -> Result<DesyncPlan, ripdpi_strategy_trait::StrategyError> {
    let conn = ConnectionState::default();
    let caps = Capabilities::default();
    let ctx = StrategyContext { dissect, conn: &conn, caps: &caps, flow_id: FlowId(flow), payload, direction };
    let mut plan = DesyncPlan::default();
    engine.make_socket_strategy(function, true).unwrap().plan(&ctx, &mut plan)?;
    Ok(plan)
}

fn bytes(plan: &DesyncPlan) -> Vec<u8> {
    plan.actions
        .iter()
        .flat_map(|action| match action {
            DesyncAction::Write(bytes) => bytes.clone(),
            _ => panic!("unexpected socket action: {action:?}"),
        })
        .collect()
}

fn http_dissect() -> Dissect {
    let mut dissect = Dissect {
        proto: L7Protocol::Http(HttpDissect { host: Some("example.com".to_owned()), is_request: true }),
        ..Dissect::default()
    };
    let start = HTTP.windows(11).position(|window| window == b"example.com").unwrap();
    dissect.markers.insert(MarkerName::Host, start);
    dissect.markers.insert(MarkerName::HostEnd, start + 11);
    dissect
}

#[test]
fn selected_bundled_http_functions_modify_exact_payload_bytes() {
    let engine = engine();
    for (function, expected) in [
        ("http_domcase", b"GET / HTTP/1.1\r\nHost: ExAmPlE.CoM\r\nUser-Agent: abcdef\r\n\r\n".as_slice()),
        ("http_hostcase", b"GET / HTTP/1.1\r\nhost: example.com\r\nUser-Agent: abcdef\r\n\r\n".as_slice()),
        ("http_methodeol", b"\r\nGET / HTTP/1.1\r\nHost: example.com\r\nUser-Agent: abcd\r\n\r\n".as_slice()),
        ("http_unixeol", b"GET / HTTP/1.1\nHost: example.com\nUser-Agent: abcdef    \n\n".as_slice()),
    ] {
        let result = plan(&engine, function, HTTP, &http_dissect(), FlowDirection::Outbound, 1).unwrap();
        assert_eq!(bytes(&result), expected, "{function}");
        assert_eq!(result.verdict, StrategyVerdict::Apply);
    }
}

#[test]
fn selected_bundled_http_functions_skip_wrong_direction_and_replies() {
    let engine = engine();
    let reply = Dissect { proto: L7Protocol::Http(HttpDissect::default()), ..Dissect::default() };
    for function in ["http_domcase", "http_hostcase", "http_methodeol", "http_unixeol"] {
        for (dissect, direction) in [(&http_dissect(), FlowDirection::Inbound), (&reply, FlowDirection::Outbound)] {
            let result = plan(&engine, function, HTTP, dissect, direction, 2).unwrap();
            assert!(result.actions.is_empty(), "{function}");
        }
    }
}

#[test]
fn selected_bundled_segmentation_preserves_multi_mss_payload() {
    let engine = engine();
    engine
        .load_bytes_registering_globals(
            "wrapper",
            br#"
        function complete_tcpseg(ctx,d) d.arg.pos='0,-1'; tcpseg(ctx,d); return VERDICT_DROP end
    "#,
        )
        .unwrap();
    let dissect = Dissect {
        proto: L7Protocol::Tls(TlsDissect { is_client_hello: true, ..TlsDissect::default() }),
        ..Dissect::default()
    };
    let payload: Vec<u8> = (0..4381).map(|value| (value % 251) as u8).collect();
    for function in ["multisplit", "complete_tcpseg"] {
        let result = plan(&engine, function, &payload, &dissect, FlowDirection::Outbound, 3).unwrap();
        assert_eq!(result.verdict, StrategyVerdict::Apply);
        assert_eq!(bytes(&result), payload, "{function}");
        assert!(result.actions.len() >= 4);
        if function == "multisplit" {
            assert_eq!(result.actions[0], DesyncAction::Write(payload[..2].to_vec()));
        }
    }
}

#[test]
fn selected_position_helpers_match_pinned_abi() {
    let engine = engine();
    let mut dissect = http_dissect();
    dissect.markers.insert(MarkerName::Host, 3);
    let cases = [
        ("resolve_pos(d.dis.payload,d.l7payload,'2')", "3"),
        ("resolve_pos(d.dis.payload,d.l7payload,'-1')", "10"),
        ("resolve_pos(d.dis.payload,d.l7payload,'host+1')", "5"),
        ("resolve_pos(d.dis.payload,d.l7payload,'host-1',true)", "2"),
        ("resolve_pos(d.dis.payload,d.l7payload,'10')", "nil"),
        ("table.concat(resolve_multi_pos(d.dis.payload,d.l7payload,'5,2,2,-1,host'),',')", "3,4,6,10"),
        ("table.concat(resolve_multi_pos(d.dis.payload,d.l7payload,'5,2,2,-1',true),',')", "2,5,9"),
        ("table.concat(resolve_range(d.dis.payload,d.l7payload,'sld,-1'),',')", "1,10"),
        ("tostring(resolve_range(d.dis.payload,d.l7payload,'sld,-1',true))", "nil"),
        ("table.concat(resolve_range(d.dis.payload,d.l7payload,'0,-1',false,true),',')", "0,9"),
        ("tostring(resolve_range(d.dis.payload,d.l7payload,'9,0'))", "nil"),
    ];
    for (expression, expected) in cases {
        let script = format!("function probe(d) return tostring({expression}) end");
        engine.load_bytes_registering_globals("probe", script.as_bytes()).unwrap();
        let result = plan(&engine, "probe", b"0123456789", &dissect, FlowDirection::Outbound, 4).unwrap();
        assert_eq!(bytes(&result), expected.as_bytes(), "{expression}");
    }
    for spec in ["bogus", "host+", "32768", "1,,2", "1,", "1,2,3"] {
        let script = format!("function probe(d) return resolve_range(d.dis.payload,d.l7payload,'{spec}') end");
        engine.load_bytes_registering_globals("probe", script.as_bytes()).unwrap();
        assert!(plan(&engine, "probe", HTTP, &dissect, FlowDirection::Outbound, 4).is_err(), "{spec}");
    }
}

#[test]
fn selected_sequence_arithmetic_wraps_and_track_state_lives_with_flow() {
    let engine = engine();
    engine
        .load_bytes_registering_globals(
            "state",
            br#"
        function counter(d)
            d.track.lua_state.calls = (d.track.lua_state.calls or 0)+1
            return d.func_instance..':'..d.track.lua_state.calls..':'..u32add(4294967295,2,-1)
        end
    "#,
        )
        .unwrap();
    for (flow, expected) in [(1, "counter:1:0"), (1, "counter:2:0"), (2, "counter:1:0")] {
        let result = plan(&engine, "counter", HTTP, &http_dissect(), FlowDirection::Outbound, flow).unwrap();
        assert_eq!(bytes(&result), expected.as_bytes());
    }
    engine.close_connection(FlowId(1)).unwrap();
    let result = plan(&engine, "counter", HTTP, &http_dissect(), FlowDirection::Outbound, 1).unwrap();
    assert_eq!(bytes(&result), b"counter:1:0");
}

#[test]
fn selected_drop_consumes_without_a_write() {
    let engine = engine();
    let result = plan(&engine, "drop", HTTP, &http_dissect(), FlowDirection::Outbound, 5).unwrap();
    assert_eq!(result.verdict, StrategyVerdict::Drop);
    assert!(result.actions.is_empty());
}

#[test]
fn selected_helpers_reject_invalid_arguments_and_bound_marker_lists() {
    let engine = engine();
    let expressions = [
        "u32add()".to_owned(),
        "u32add(4294967296)".to_owned(),
        "u32add(-4294967296)".to_owned(),
        "u32add(1.5)".to_owned(),
        "u32add(true)".to_owned(),
        format!("u32add({})", vec!["1"; 101].join(",")),
        "resolve_pos(d.dis.payload,'invalid','2')".to_owned(),
        format!("resolve_multi_pos(d.dis.payload,d.l7payload,'{}')", vec!["2"; 129].join(",")),
    ];
    for expression in expressions {
        let script = format!("function invalid(d) {expression} end");
        engine.load_bytes_registering_globals("invalid", script.as_bytes()).unwrap();
        assert!(plan(&engine, "invalid", HTTP, &http_dissect(), FlowDirection::Outbound, 6).is_err(), "{expression}");
    }
    let script = format!(
        "function valid(d) return table.concat(resolve_multi_pos(d.dis.payload,d.l7payload,'{}'),',') end",
        vec!["2"; 128].join(",")
    );
    engine.load_bytes_registering_globals("valid", script.as_bytes()).unwrap();
    let result = plan(&engine, "valid", HTTP, &http_dissect(), FlowDirection::Outbound, 6).unwrap();
    assert_eq!(bytes(&result), b"3");
}

#[test]
fn selected_bundled_http_functions_preserve_missing_header_and_short_user_agent() {
    let engine = engine();
    let dissect = Dissect {
        proto: L7Protocol::Http(HttpDissect { is_request: true, ..HttpDissect::default() }),
        ..Dissect::default()
    };
    for function in ["http_domcase", "http_hostcase", "http_methodeol", "http_unixeol"] {
        let result = plan(&engine, function, b"GET / HTTP/1.1\r\n\r\n", &dissect, FlowDirection::Outbound, 7).unwrap();
        assert!(result.actions.is_empty(), "{function}");
    }
    let result = plan(
        &engine,
        "http_methodeol",
        b"GET / HTTP/1.1\r\nUser-Agent: ab\r\n\r\n",
        &dissect,
        FlowDirection::Outbound,
        7,
    )
    .unwrap();
    assert!(result.actions.is_empty());
    engine
        .load_bytes_registering_globals(
            "invalid-host",
            br#"
        function invalid_host(ctx,d) d.arg.spell='bad'; return http_hostcase(ctx,d) end
    "#,
        )
        .unwrap();
    assert!(plan(&engine, "invalid_host", HTTP, &http_dissect(), FlowDirection::Outbound, 7).is_err());
}

#[test]
fn selected_range_helper_is_available_on_packet_planner_and_unsupported_options_still_fail() {
    let engine = engine();
    engine
        .load_bytes_registering_globals(
            "range",
            br#"
        function range_probe(d) return table.concat(resolve_range(d.dis.payload,d.l7payload,'0,-1'),',') end
        function unsupported_split(ctx,d) d.arg.ip_ttl='3'; return multisplit(ctx,d) end
    "#,
        )
        .unwrap();
    let dissect = http_dissect();
    let conn = ConnectionState::default();
    let caps = Capabilities::default();
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &conn,
        caps: &caps,
        flow_id: FlowId(8),
        payload: b"abcd",
        direction: FlowDirection::Outbound,
    };
    let mut result = DesyncPlan::default();
    engine.make_strategy("range_probe").unwrap().plan(&ctx, &mut result).unwrap();
    assert_eq!(bytes(&result), b"1,4");
    assert!(plan(&engine, "unsupported_split", HTTP, &dissect, FlowDirection::Outbound, 8).is_err());
}

#[test]
fn selected_payload_names_keep_protocol_family_api_and_distinguish_non_client_tls() {
    let engine = engine();
    engine
        .load_bytes_registering_globals(
            "types",
            br#"
        function type_probe(d) return d.dis.proto..':'..d.l7payload..':'..tostring(d.detect(d.dis.proto)) end
    "#,
        )
        .unwrap();
    let cases = [
        (http_dissect(), HTTP, "http:http_req:true"),
        (
            Dissect { proto: L7Protocol::Http(HttpDissect::default()), ..Dissect::default() },
            HTTP,
            "http:http_reply:true",
        ),
        (
            Dissect {
                proto: L7Protocol::Tls(TlsDissect { is_client_hello: true, ..TlsDissect::default() }),
                ..Dissect::default()
            },
            b"\x16\x03\x03\x00\x01\x01".as_slice(),
            "tls:tls_client_hello:true",
        ),
        (
            Dissect { proto: L7Protocol::Tls(TlsDissect::default()), ..Dissect::default() },
            b"\x16\x03\x03\x00\x01\x02".as_slice(),
            "tls:tls_server_hello:true",
        ),
        (
            Dissect { proto: L7Protocol::Tls(TlsDissect::default()), ..Dissect::default() },
            b"\x17\x03\x03\x00\x01\x01".as_slice(),
            "tls:unknown:true",
        ),
        (http_dissect(), b"".as_slice(), "http:empty:true"),
    ];
    for (dissect, payload, expected) in cases {
        let result = plan(&engine, "type_probe", payload, &dissect, FlowDirection::Outbound, 9).unwrap();
        assert_eq!(bytes(&result), expected.as_bytes());
    }
}

#[test]
fn alternate_blobs_reject_original_host_markers() {
    let engine = engine();
    for helper in ["resolve_pos", "resolve_multi_pos", "resolve_range"] {
        let marker = if helper == "resolve_range" { "host,-1" } else { "host" };
        let script = format!("function alternate(d) return {helper}('other payload','http_req','{marker}') end");
        engine.load_bytes_registering_globals("alternate", script.as_bytes()).unwrap();
        assert!(plan(&engine, "alternate", HTTP, &http_dissect(), FlowDirection::Outbound, 11).is_err(), "{helper}");
    }
    engine
        .load_bytes_registering_globals(
            "absolute",
            b"function absolute(d) return tostring(resolve_pos('abcd','http_req','2')) end",
        )
        .unwrap();
    assert_eq!(bytes(&plan(&engine, "absolute", HTTP, &http_dissect(), FlowDirection::Outbound, 11).unwrap()), b"3");
}

#[test]
fn protocol_relative_positions_do_not_reuse_markers_for_another_payload_type() {
    let engine = engine();
    for (mut dissect, requested_type) in [(http_dissect(), "tls_client_hello"), (http_dissect(), "http_req")] {
        if requested_type == "http_req" {
            dissect.proto = L7Protocol::Tls(TlsDissect { is_client_hello: true, ..TlsDissect::default() });
        }
        let script = format!(
            "function cross_type(d) return tostring(resolve_pos(d.dis.payload,'{requested_type}','host'))..':'..table.concat(resolve_multi_pos(d.dis.payload,'{requested_type}','host'),',')..':'..tostring(resolve_range(d.dis.payload,'{requested_type}','host,endhost')) end"
        );
        engine.load_bytes_registering_globals("cross-type", script.as_bytes()).unwrap();
        let result = plan(&engine, "cross_type", HTTP, &dissect, FlowDirection::Outbound, 12).unwrap();
        assert_eq!(bytes(&result), b"nil::nil");
    }
}

#[test]
fn measured_mss_controls_lua_and_bundled_tcpseg() {
    let engine = engine();
    engine
        .load_bytes_registering_globals(
            "mss-wrapper",
            br#"
        function report_mss(d) return tostring(d.tcp_mss) end
        function measured_tcpseg(ctx,d) d.arg.pos='0,-1'; tcpseg(ctx,d); return VERDICT_DROP end
    "#,
        )
        .unwrap();
    let payload: Vec<u8> = (0..4381).map(|value| (value % 251) as u8).collect();
    for supplied in [Some(536), Some(1200), Some(1448), Some(1), Some(u16::MAX), Some(0), None] {
        let mss = supplied.filter(|value| *value > 0).unwrap_or(1460);
        let dissect = Dissect {
            tcp_mss: supplied,
            proto: L7Protocol::Tls(TlsDissect { is_client_hello: true, ..TlsDissect::default() }),
            ..Dissect::default()
        };
        let report = plan(&engine, "report_mss", b"x", &dissect, FlowDirection::Outbound, 40).unwrap();
        assert_eq!(bytes(&report), mss.to_string().as_bytes());
        let result = plan(&engine, "measured_tcpseg", &payload, &dissect, FlowDirection::Outbound, 41).unwrap();
        assert_eq!(result.verdict, StrategyVerdict::Apply, "MSS {mss}");
        assert_eq!(bytes(&result), payload);
        assert!(result.actions.iter().all(|action| matches!(action,
            DesyncAction::Write(bytes) if !bytes.is_empty() && bytes.len() <= usize::from(mss))));
    }
}
