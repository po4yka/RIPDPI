use ripdpi_proxy_config::{parse_proxy_config_json, runtime_config_envelope_from_payload};
use serde_json::json;

#[test]
fn command_line_payload_selects_plaintext_address_input() {
    for mixed in [false, true] {
        let mut args = vec!["ripdpi", "--shadowsocks-plain"];
        if mixed {
            args.push("--mixed");
        }
        let payload =
            parse_proxy_config_json(&json!({"kind": "command_line", "schemaVersion": 2, "args": args}).to_string())
                .expect("Android command-line payload");
        let config = runtime_config_envelope_from_payload(payload).expect("plaintext command-line config").config;

        assert!(config.network.shadowsocks);
        assert_eq!(config.network.mixed, mixed);
        assert!(config.network.listen.listen_ip.is_loopback());
        assert_eq!(config.network.listen.auth_token, None);
    }
}

#[test]
fn plaintext_command_line_payload_preserves_required_session_auth() {
    let payload = parse_proxy_config_json(
        &json!({
            "kind": "command_line",
            "schemaVersion": 2,
            "args": ["--shadowsocks-plain"],
            "sessionOverrides": {"authToken": "local-test-token", "listenPortOverride": 0}
        })
        .to_string(),
    )
    .expect("authenticated session payload");
    let config = runtime_config_envelope_from_payload(payload).expect("session overrides preserved").config;

    assert!(config.network.shadowsocks);
    assert_eq!(config.network.listen.listen_port, 0);
    assert_eq!(config.network.listen.auth_token.as_deref(), Some("local-test-token"));
}
