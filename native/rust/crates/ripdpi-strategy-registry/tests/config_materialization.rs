use ripdpi_strategy_config::parse_yaml_str;
use ripdpi_strategy_registry::StrategyRegistry;
use ripdpi_strategy_trait::{
    Capabilities, CapabilityTier, ConnectionState, DesyncAction, DesyncPlan, Dissect, FlowDirection, FlowId,
    HttpDissect, L7Protocol, QuicDissect, RuntimeCapability, StrategyContext, StrategyVerdict,
};

#[test]
fn parsed_yaml_config_materializes_concrete_registry_entries() {
    let config = parse_yaml_str(
        r#"
version: 1
strategies:
  - id: http-chain
    match:
      proto: [http]
    on_fail: fallback_plain
    steps:
      - type: httpDomcase
      - type: wsize
        value: 4
  - id: udp-chain
    match:
      proto: [quic]
    steps:
      - type: udplen
        delta: 4
"#,
        ".",
    )
    .expect("YAML config should parse");

    let registry = StrategyRegistry::from_loaded_config(&config).expect("config should materialize");
    let ids = registry.list().map(|descriptor| descriptor.id.as_str()).collect::<Vec<_>>();

    assert_eq!(ids, ["http_domcase", "wsize", "udplen"]);
    assert!(registry.get("http_domcase").is_some());
    assert!(registry.get("wsize").is_some());
    assert!(registry.get("udplen").is_some());
}

#[test]
fn parsed_yaml_http_strategy_executes_payload_transform() {
    let config = parse_yaml_str(
        r#"
version: 1
strategies:
  - id: http-chain
    match:
      proto: [http]
    steps:
      - type: httpDomcase
"#,
        ".",
    )
    .expect("YAML config should parse");
    let registry = StrategyRegistry::from_loaded_config(&config).expect("config should materialize");
    let payload = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";
    let dissect = Dissect {
        proto: L7Protocol::Http(HttpDissect { host: Some("example.com".to_owned()), is_request: true }),
        ..Dissect::default()
    };
    let conn = ConnectionState::default();
    let caps = Capabilities::default();
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &conn,
        caps: &caps,
        flow_id: FlowId(7),
        payload,
        direction: FlowDirection::Outbound,
    };
    let mut plan = DesyncPlan::default();

    let verdict = registry.execute(&ctx, &mut plan);

    assert_eq!(verdict, StrategyVerdict::Apply);
    assert_eq!(plan.actions, vec![DesyncAction::Write(b"GET / HTTP/1.1\r\nHost: eXaMpLe.CoM\r\n\r\n".to_vec())]);
}

#[test]
fn parsed_yaml_fake_strategy_executes_typed_fake_action() {
    let config = parse_yaml_str(
        r#"
version: 1
strategies:
  - id: fake-chain
    steps:
      - type: fake
"#,
        ".",
    )
    .expect("YAML config should parse");
    let registry = StrategyRegistry::from_loaded_config(&config).expect("config should materialize");
    let dissect = Dissect::default();
    let conn = ConnectionState::default();
    let caps = Capabilities::default();
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &conn,
        caps: &caps,
        flow_id: FlowId(9),
        payload: b"payload",
        direction: FlowDirection::Outbound,
    };
    let mut plan = DesyncPlan::default();

    let verdict = registry.execute(&ctx, &mut plan);

    assert_eq!(verdict, StrategyVerdict::Apply);
    assert_eq!(plan.actions, vec![DesyncAction::WriteFake { ttl: None, sni_mode: None, payload_file: None }]);
}

#[test]
fn parsed_yaml_udplen_strategy_uses_configured_delta() {
    let config = parse_yaml_str(
        r#"
version: 1
strategies:
  - id: udp-chain
    steps:
      - type: udplen
        delta: 6
"#,
        ".",
    )
    .expect("YAML config should parse");
    let registry = StrategyRegistry::from_loaded_config(&config).expect("config should materialize");
    let packet = ipv4_udp_packet(443, 443, b"abc");
    let dissect =
        Dissect { proto: L7Protocol::Quic(QuicDissect::default()), src_port: 443, dst_port: 443, ..Dissect::default() };
    let conn = ConnectionState::default();
    let caps = vpn_caps();
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &conn,
        caps: &caps,
        flow_id: FlowId(10),
        payload: &packet,
        direction: FlowDirection::Outbound,
    };
    let mut plan = DesyncPlan::default();

    let verdict = registry.execute(&ctx, &mut plan);

    assert_eq!(verdict, StrategyVerdict::Apply);
    let [DesyncAction::RawSend(output)] = plan.actions.as_slice() else {
        panic!("expected a single RawSend action, got {:?}", plan.actions);
    };
    let udp_len_offset = 20 + 4;
    assert_eq!(u16::from_be_bytes([output[udp_len_offset], output[udp_len_offset + 1]]), 17);
}

#[test]
fn parsed_yaml_ipv6_ext_strategy_uses_configured_extension_type() {
    let config = parse_yaml_str(
        r#"
version: 1
strategies:
  - id: ipv6-chain
    steps:
      - type: ipv6Ext
        ext_type: hopbyhop
"#,
        ".",
    )
    .expect("YAML config should parse");
    let registry = StrategyRegistry::from_loaded_config(&config).expect("config should materialize");
    let packet = ipv6_tcp_packet();
    let dissect = Dissect { is_ipv6: true, ..Dissect::default() };
    let conn = ConnectionState::default();
    let caps = vpn_caps();
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &conn,
        caps: &caps,
        flow_id: FlowId(11),
        payload: &packet,
        direction: FlowDirection::Outbound,
    };
    let mut plan = DesyncPlan::default();

    let verdict = registry.execute(&ctx, &mut plan);

    assert_eq!(verdict, StrategyVerdict::Apply);
    let [DesyncAction::RawSend(output)] = plan.actions.as_slice() else {
        panic!("expected a single RawSend action, got {:?}", plan.actions);
    };
    assert_eq!(output[6], 0, "hop-by-hop extension must be the outer next-header");
}

#[cfg(feature = "lua-strategies")]
#[test]
fn parsed_yaml_lua_strategy_materializes_rawsend_action() {
    let dir = std::env::temp_dir().join(format!("ripdpi-lua-strategy-{}", std::process::id()));
    std::fs::create_dir_all(&dir).expect("create temp dir");
    let script_path = dir.join("candidate.lua");
    std::fs::write(
        &script_path,
        r#"
function candidate(desync)
    desync.rawsend("lua-raw")
    return VERDICT_MODIFY
end
"#,
    )
    .expect("write Lua script");
    let yaml = format!(
        r#"
version: 1
strategies:
  - id: lua-candidate
    steps:
      - type: lua
        function: candidate
        script_paths:
          - "{}"
"#,
        script_path.display()
    );
    let config = parse_yaml_str(&yaml, ".").expect("YAML config should parse");
    let registry = StrategyRegistry::from_loaded_config(&config).expect("config should materialize");
    let dissect = Dissect::default();
    let conn = ConnectionState::default();
    let caps = vpn_caps();
    let ctx = StrategyContext {
        dissect: &dissect,
        conn: &conn,
        caps: &caps,
        flow_id: FlowId(12),
        payload: b"payload",
        direction: FlowDirection::Outbound,
    };
    let mut plan = DesyncPlan::default();

    let verdict = registry.execute(&ctx, &mut plan);

    assert_eq!(verdict, StrategyVerdict::Apply);
    assert_eq!(plan.actions, vec![DesyncAction::RawSend(b"lua-raw".to_vec())]);
    let _ = std::fs::remove_dir_all(dir);
}

fn vpn_caps() -> Capabilities {
    Capabilities { tier: CapabilityTier::Tier3, available: vec![RuntimeCapability::VpnMode] }
}

fn ipv4_udp_packet(src_port: u16, dst_port: u16, payload: &[u8]) -> Vec<u8> {
    let total_len = 20 + 8 + payload.len();
    let mut packet = vec![0u8; total_len];
    packet[0] = 0x45;
    packet[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
    packet[8] = 64;
    packet[9] = 17;
    packet[12..16].copy_from_slice(&[10, 0, 0, 2]);
    packet[16..20].copy_from_slice(&[93, 184, 216, 34]);
    packet[20..22].copy_from_slice(&src_port.to_be_bytes());
    packet[22..24].copy_from_slice(&dst_port.to_be_bytes());
    packet[24..26].copy_from_slice(&((8 + payload.len()) as u16).to_be_bytes());
    packet[28..].copy_from_slice(payload);
    packet
}

fn ipv6_tcp_packet() -> Vec<u8> {
    let mut packet = vec![0u8; 60];
    packet[0] = 0x60;
    packet[4..6].copy_from_slice(&20u16.to_be_bytes());
    packet[6] = 6;
    packet[7] = 64;
    packet[8..24].copy_from_slice(&std::net::Ipv6Addr::LOCALHOST.octets());
    packet[24..40].copy_from_slice(&std::net::Ipv6Addr::LOCALHOST.octets());
    packet[40..42].copy_from_slice(&49152u16.to_be_bytes());
    packet[42..44].copy_from_slice(&443u16.to_be_bytes());
    packet[52] = 0x50;
    packet[53] = 0x18;
    packet[54..56].copy_from_slice(&65535u16.to_be_bytes());
    packet
}
