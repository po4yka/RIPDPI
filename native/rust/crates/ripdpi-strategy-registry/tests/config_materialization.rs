use ripdpi_strategy_config::parse_yaml_str;
use ripdpi_strategy_registry::StrategyRegistry;

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
