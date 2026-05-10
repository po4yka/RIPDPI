use ripdpi_strategy_config::parse_yaml_str;
use ripdpi_strategy_registry::StrategyRegistry;
use ripdpi_strategy_trait::{
    Capabilities, ConnectionState, DesyncAction, DesyncPlan, Dissect, FlowDirection, FlowId, HttpDissect, L7Protocol,
    StrategyContext, StrategyVerdict,
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
