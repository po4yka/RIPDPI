/// Cross-language round-trip tests for the config contract.
///
/// Fixture source of truth: core/engine/src/test/resources/fixtures/
/// The Kotlin ConfigContractRoundTripTest commits these fixtures.
/// Path is anchored at CARGO_MANIFEST_DIR so the test is robust regardless
/// of the cargo working directory.
///
/// Steps for each fixture:
/// 1. Read JSON from the fixture file.
/// 2. Parse into ProxyConfigPayload via parse_proxy_config_json.
/// 3. Re-serialize back to JSON string.
/// 4. Parse the re-serialized JSON (second parse).
/// 5. Assert that second parse == first parse (reflexive typed equality).
/// 6. Assert that re-serializing the second parse produces the same bytes (idempotence).
use ripdpi_proxy_config::{parse_proxy_config_json, ProxyConfigPayload};

fn fixture_path(name: &str) -> std::path::PathBuf {
    // CARGO_MANIFEST_DIR = .../native/rust/crates/ripdpi-proxy-config
    // Fixture root is repo_root/core/engine/src/test/resources/fixtures
    // = ../../../../core/engine/... (4 levels up from the crate dir)
    std::path::Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../../../core/engine/src/test/resources/fixtures")
        .join(name)
}

fn read_fixture(name: &str) -> String {
    let path = fixture_path(name);
    std::fs::read_to_string(&path).unwrap_or_else(|err| {
        panic!(
            "fixture '{}' not found at '{}': {err}\n\
             Run ':core:engine:test' first to generate fixture files.",
            name,
            path.display()
        )
    })
}

fn round_trip_assert(fixture_name: &str) {
    let json = read_fixture(fixture_name);

    // Parse 1
    let first: ProxyConfigPayload =
        parse_proxy_config_json(&json).unwrap_or_else(|err| panic!("parse 1 of '{fixture_name}' failed: {err}"));

    // Re-serialize
    let json2 =
        serde_json::to_string(&first).unwrap_or_else(|err| panic!("serialize of '{fixture_name}' failed: {err}"));

    // Parse 2
    let second: ProxyConfigPayload =
        parse_proxy_config_json(&json2).unwrap_or_else(|err| panic!("parse 2 of '{fixture_name}' failed: {err}"));

    // Reflexive typed equality
    assert_eq!(first, second, "typed model mismatch after re-serialization for '{fixture_name}'");

    // Idempotence: re-serialize second parse and compare bytes
    let json3 =
        serde_json::to_string(&second).unwrap_or_else(|err| panic!("serialize 2 of '{fixture_name}' failed: {err}"));

    assert_eq!(json2, json3, "re-serialization is not idempotent for '{fixture_name}'");
}

#[test]
fn tcp_heavy_chain_round_trips() {
    round_trip_assert("round-trip-tcp-heavy.json");
}

#[test]
fn udp_quic_chain_round_trips() {
    round_trip_assert("round-trip-udp-quic.json");
}

#[test]
fn relay_heavy_config_round_trips() {
    round_trip_assert("round-trip-relay-heavy.json");
}
