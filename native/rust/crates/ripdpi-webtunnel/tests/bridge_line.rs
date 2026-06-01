use golden_test_support::{assert_text_golden, canonicalize_json};
use ripdpi_webtunnel::bridge_line::{WebTunnelBridgeConfig, parse_bridge_line};
use serde::Serialize;

#[derive(Serialize)]
struct ParsedCase<'a> {
    name: &'a str,
    config: WebTunnelBridgeConfig,
}

#[test]
fn bridge_line_parser_matches_go_derived_goldens() {
    let cases = [
        (
            "bridge-prefix-defaults",
            "Bridge webtunnel 192.0.2.3:1 url=https://front.example/secret-path version=1 utls=hellochrome_auto",
        ),
        (
            "explicit-addr-servername",
            "webtunnel 198.51.100.10:443 url=https://cdn.example/a%2Fb addr=203.0.113.9:9443 servername=real.example utls=hellochrome_auto",
        ),
        ("defaults-with-query-ignored", "Bridge webtunnel 192.0.2.3:1 url=https://front.example:8443/secret?ignored=1"),
        (
            "cert-options-preserved",
            "Bridge webtunnel 192.0.2.3:1 url=https://front.example/s cert=YWJj cert-domain=verify.example",
        ),
    ];
    let parsed = cases
        .into_iter()
        .map(|(name, line)| ParsedCase { name, config: parse_bridge_line(line).expect(name) })
        .collect::<Vec<_>>();
    let actual = serde_json::to_string_pretty(&parsed).expect("serialize parsed bridge cases");
    let actual = canonicalize_json(&actual).expect("canonicalize parsed bridge cases");

    assert_text_golden(env!("CARGO_MANIFEST_DIR"), "tests/golden/bridge_line_cases.json", &actual);
}

#[test]
fn bridge_line_parser_rejects_invalid_inputs() {
    let invalid = [
        ("missing-url", "Bridge webtunnel 192.0.2.3:1 version=1"),
        ("wrong-transport", "Bridge obfs4 192.0.2.3:1 url=https://front.example/s"),
        ("missing-secret-path", "Bridge webtunnel 192.0.2.3:1 url=https://front.example"),
        ("non-https", "Bridge webtunnel 192.0.2.3:1 url=http://front.example/s"),
        ("malformed-option", "Bridge webtunnel 192.0.2.3:1 url=https://front.example/s malformed"),
        ("empty-servername", "Bridge webtunnel 192.0.2.3:1 url=https://front.example/s servername="),
        ("unknown-utls", "Bridge webtunnel 192.0.2.3:1 url=https://front.example/s utls=hellogopher_1"),
    ];

    for (name, line) in invalid {
        assert!(parse_bridge_line(line).is_err(), "{name} should be rejected");
    }
}
