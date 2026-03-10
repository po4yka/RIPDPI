use std::sync::OnceLock;

use ciadpi_config::{parse_cli, DesyncMode, ParseResult, RuntimeConfig, StartupEnv};
#[cfg(any(target_os = "linux", target_os = "windows"))]
use ciadpi_desync::build_fake_packet;
use ciadpi_desync::{plan_tcp, plan_udp, DesyncAction};
use serde_json::Value;

#[allow(dead_code)]
#[path = "../../../tests/rust_packet_seeds.rs"]
mod rust_packet_seeds;

fn fixtures() -> &'static Value {
    static FIXTURES: OnceLock<Value> = OnceLock::new();
    FIXTURES.get_or_init(|| {
        serde_json::from_str(include_str!(
            "../../../tests/corpus/rust-fixtures/desync_oracle.json"
        ))
        .expect("desync fixtures")
    })
}

fn fixture(case: &str) -> &'static Value {
    &fixtures()[case]
}

fn parse_group(args: &[&str]) -> (RuntimeConfig, usize) {
    let args: Vec<String> = args.iter().map(|value| (*value).to_owned()).collect();
    let config = match parse_cli(&args, &StartupEnv::default()).expect("parse config") {
        ParseResult::Run(config) => config,
        other => panic!("expected runtime config, got {other:?}"),
    };
    let idx = config.actionable_group();
    (config, idx)
}

fn hex(data: &[u8]) -> String {
    const HEX: &[u8; 16] = b"0123456789abcdef";
    let mut out = String::with_capacity(data.len() * 2);
    for byte in data {
        out.push(HEX[(byte >> 4) as usize] as char);
        out.push(HEX[(byte & 0x0f) as usize] as char);
    }
    out
}

#[test]
fn mod_http_and_split_plan_matches_fixture() {
    let expected = fixture("mod_http_and_split_plan");
    let payload = rust_packet_seeds::http_request();
    let (config, idx) = parse_group(&["--mod-http", "rh", "--split", "8"]);
    let plan = plan_tcp(&config.groups[idx], &payload, 7, config.default_ttl).expect("plan");

    assert_eq!(
        hex(&plan.tampered),
        expected["tampered_hex"].as_str().unwrap()
    );
    assert_eq!(
        plan.steps[0].mode as u64,
        expected["step_mode"].as_u64().unwrap()
    );
    assert_eq!(
        plan.steps[0].end as i64,
        expected["step_end"].as_i64().unwrap()
    );
}

#[test]
fn tls_record_split_plan_matches_fixture() {
    let expected = fixture("tls_record_split_plan");
    let payload = rust_packet_seeds::tls_client_hello();
    let (config, idx) = parse_group(&["--tlsrec", "32"]);
    let plan = plan_tcp(&config.groups[idx], &payload, 7, config.default_ttl).expect("plan");

    assert_eq!(
        plan.tampered.len() as u64,
        expected["tampered_len"].as_u64().unwrap()
    );
    assert_eq!(
        hex(&plan.tampered),
        expected["tampered_hex"].as_str().unwrap()
    );
    assert_eq!(plan.proto.kind, 0);
}

#[test]
fn tlsminor_plan_matches_fixture() {
    let expected = fixture("tlsminor_plan");
    let payload = rust_packet_seeds::tls_client_hello();
    let (config, idx) = parse_group(&["--tlsminor", "5"]);
    let plan = plan_tcp(&config.groups[idx], &payload, 7, config.default_ttl).expect("plan");

    assert_eq!(
        hex(&plan.tampered),
        expected["tampered_hex"].as_str().unwrap()
    );
}

#[test]
fn host_offset_plans_match_fixture() {
    let http_expected = fixture("http_host_offset_plan");
    let http_payload = rust_packet_seeds::http_request();
    let (http_config, http_idx) = parse_group(&["--split", "0+h"]);
    let http_plan = plan_tcp(
        &http_config.groups[http_idx],
        &http_payload,
        7,
        http_config.default_ttl,
    )
    .expect("http plan");

    assert_eq!(
        http_plan.steps[0].end as i64,
        http_expected["step_end"].as_i64().unwrap()
    );

    let tls_expected = fixture("tls_host_offset_plan");
    let tls_payload = rust_packet_seeds::tls_client_hello();
    let (tls_config, tls_idx) = parse_group(&["--split", "0+s"]);
    let tls_plan = plan_tcp(
        &tls_config.groups[tls_idx],
        &tls_payload,
        7,
        tls_config.default_ttl,
    )
    .expect("tls plan");

    assert_eq!(
        tls_plan.steps[0].end as i64,
        tls_expected["step_end"].as_i64().unwrap()
    );
}

#[test]
fn desync_modes_map_to_distinct_plans() {
    for (flag, expected_mode) in [
        ("--split", DesyncMode::Split),
        ("--disorder", DesyncMode::Disorder),
        ("--oob", DesyncMode::Oob),
        ("--disoob", DesyncMode::Disoob),
        ("-f", DesyncMode::Fake),
    ] {
        let (config, idx) = parse_group(&[flag, "8"]);
        let plan = plan_tcp(
            &config.groups[idx],
            b"GET / HTTP/1.1\r\nHost: www.wikipedia.org\r\n\r\n",
            7,
            config.default_ttl,
        )
        .expect("plan");

        assert_eq!(plan.steps[0].mode, expected_mode, "{flag}");
        assert_eq!(plan.steps[0].end, 8, "{flag}");
    }
}

#[test]
#[cfg(any(target_os = "linux", target_os = "windows"))]
fn fake_packet_can_rewrite_tls_sni() {
    let expected = fixture("fake_packet_tls_sni");
    let payload = rust_packet_seeds::tls_client_hello();
    let (config, idx) = parse_group(&[
        "-f",
        "-1",
        "--fake-sni",
        "docs.example.test",
        "--fake-tls-mod",
        "orig",
    ]);
    let fake = build_fake_packet(&config.groups[idx], &payload, 7).expect("fake plan");

    assert_eq!(hex(&fake.bytes), expected["fake_hex"].as_str().unwrap());
    assert_eq!(
        fake.fake_offset as u64,
        expected["fake_offset"].as_u64().unwrap()
    );
}

#[test]
#[cfg(any(target_os = "linux", target_os = "windows"))]
fn fake_packet_can_use_custom_http_payload() {
    let expected = fixture("fake_packet_custom_http");
    let payload = rust_packet_seeds::http_request();
    let (config, idx) = parse_group(&[
        "-f",
        "-1",
        "--fake-data",
        ":GET / HTTP/1.1\r\nHost: fake.example.test\r\n\r\n",
        "--fake-offset",
        "1+h",
    ]);
    let fake = build_fake_packet(&config.groups[idx], &payload, 7).expect("fake plan");

    assert_eq!(hex(&fake.bytes), expected["fake_hex"].as_str().unwrap());
    assert_eq!(
        fake.fake_offset as u64,
        expected["fake_offset"].as_u64().unwrap()
    );
}

#[test]
fn udp_fake_actions_are_deterministic() {
    let (config, idx) = parse_group(&["--udp-fake", "2"]);
    let actions = plan_udp(
        &config.groups[idx],
        b"udp proxy payload",
        config.default_ttl,
    );
    assert_eq!(
        actions,
        vec![
            DesyncAction::SetTtl(8),
            DesyncAction::Write(vec![0; 64]),
            DesyncAction::Write(vec![0; 64]),
            DesyncAction::RestoreDefaultTtl,
            DesyncAction::Write(b"udp proxy payload".to_vec()),
        ]
    );
}
