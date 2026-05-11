use ripdpi_strategy_config::{parse_yaml_str, OnFail, ProtocolName, StepType};

#[test]
fn parses_canonical_yaml_schema() {
    let yaml = r#"
version: 1
strategies:
  - id: tls_split_disorder
    match:
      proto: [tls]
      port: [443, 8443]
      hosts: ["example.com"]
    steps:
      - type: split
        pos: sni
        disorder: true
        ttl: 5
      - type: fake
        ttl: 5
        sni_mode: rand
    on_fail: next_strategy
  - id: quic_udplen
    match:
      proto: [quic]
    steps:
      - type: udplen
        delta: 4
"#;

    let parsed = parse_yaml_str(yaml, ".").expect("parse yaml");
    assert_eq!(parsed.version, 1);
    assert_eq!(parsed.strategies.len(), 2);
    assert_eq!(parsed.strategies[0].id, "tls_split_disorder");
    assert_eq!(parsed.strategies[0].matcher.proto, [ProtocolName::Tls]);
    assert_eq!(parsed.strategies[0].steps.len(), 2);
    assert_eq!(parsed.strategies[0].steps[0].kind, StepType::Split);
    assert_eq!(parsed.strategies[0].on_fail, OnFail::NextStrategy);
    assert_eq!(parsed.strategies[1].steps[0].kind, StepType::Udplen);
}

#[test]
fn parses_synack_strategy_steps() {
    let yaml = r#"
version: 1
strategies:
  - id: synack_handshake
    steps:
      - type: synack
        ttl: 5
      - type: synack_split
"#;

    let parsed = parse_yaml_str(yaml, ".").expect("parse yaml");
    let steps = &parsed.strategies[0].steps;

    assert_eq!(steps[0].kind, StepType::SynAck);
    assert_eq!(steps[0].ttl, Some(5));
    assert_eq!(steps[0].kind.registry_id(), "synack");
    assert_eq!(steps[1].kind, StepType::SynAckSplit);
    assert_eq!(steps[1].kind.registry_id(), "synack_split");
}

#[test]
fn parses_lua_strategy_step() {
    let yaml = r#"
version: 1
strategies:
  - id: lua-candidate
    steps:
      - type: lua
        function: candidate
        script_paths:
          - /data/user/0/com.poyka.ripdpi/files/lua/candidate.lua
        forward_original: true
"#;

    let parsed = parse_yaml_str(yaml, ".").expect("parse yaml");
    let step = &parsed.strategies[0].steps[0];

    assert_eq!(step.kind, StepType::Lua);
    assert_eq!(step.kind.registry_id(), "lua");
    assert_eq!(step.function.as_deref(), Some("candidate"));
    assert_eq!(step.script_paths, ["/data/user/0/com.poyka.ripdpi/files/lua/candidate.lua"]);
    assert_eq!(step.forward_original, Some(true));
}
