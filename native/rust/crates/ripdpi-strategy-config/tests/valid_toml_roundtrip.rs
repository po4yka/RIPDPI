use ripdpi_strategy_config::{OnFail, ProtocolName, StepType, load_config_file, parse_toml_str};

#[test]
fn parses_canonical_toml_schema() {
    let toml = r#"
version = 1

[[strategies]]
id = "tls-default"
on_fail = "fallback_plain"

[strategies.match]
proto = ["tls", "http"]
port = [443, 80]
hosts = ["example.com", "example.org"]

[[strategies.steps]]
type = "split"
pos = "sni+1"

[[strategies.steps]]
type = "httpDomcase"

[[strategies.steps]]
type = "wsize"
value = 4
"#;

    let parsed = parse_toml_str(toml, ".").expect("parse toml");

    assert_eq!(parsed.version, 1);
    assert_eq!(parsed.strategies.len(), 1);
    let strategy = &parsed.strategies[0];
    assert_eq!(strategy.id, "tls-default");
    assert_eq!(strategy.on_fail, OnFail::FallbackPlain);
    assert_eq!(strategy.matcher.proto, [ProtocolName::Tls, ProtocolName::Http]);
    assert_eq!(strategy.matcher.port, [443, 80]);
    assert_eq!(strategy.matcher.hosts, ["example.com", "example.org"]);
    assert_eq!(strategy.steps[0].kind, StepType::Split);
    assert_eq!(strategy.steps[0].pos.as_deref(), Some("sni+1"));
    assert_eq!(strategy.steps[1].kind, StepType::HttpDomcase);
    assert_eq!(strategy.steps[2].kind, StepType::Wsize);
    assert_eq!(strategy.steps[2].value, Some(4));
}

#[test]
fn load_config_file_dispatches_toml_by_extension() {
    let dir = std::env::temp_dir().join(format!("ripdpi-strategy-toml-{}", std::process::id()));
    std::fs::create_dir_all(&dir).expect("create temp dir");
    let host_list = dir.join("hosts.txt");
    std::fs::write(&host_list, "example.net\n").expect("write hosts");
    let config_path = dir.join("strategy.toml");
    std::fs::write(
        &config_path,
        r#"
version = 1

[[strategies]]
id = "quic-udp"

[strategies.match]
proto = ["quic"]
hosts = "@hosts.txt"

[[strategies.steps]]
type = "udplen"
delta = 4
"#,
    )
    .expect("write config");

    let parsed = load_config_file(&config_path).expect("load toml config");

    assert_eq!(parsed.strategies[0].matcher.hosts, ["example.net"]);
    assert_eq!(parsed.strategies[0].steps[0].kind, StepType::Udplen);
    assert_eq!(parsed.strategies[0].steps[0].delta, Some(4));
    let _ = std::fs::remove_dir_all(dir);
}
