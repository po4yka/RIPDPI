//! Live producer-to-consumer checks run by `run-relay-config-contract.py`.

use crate::runtime::create_session;

#[test]
#[ignore = "requires the live Kotlin export; run scripts/tests/run-relay-config-contract.py"]
fn kotlin_relay_payloads_reach_the_native_session_decoder() {
    let path = std::env::var("RIPDPI_KOTLIN_RELAY_WIRE").expect("Kotlin wire export path");
    let input = std::fs::read_to_string(path).expect("read the Kotlin wire export");
    let cases: Vec<serde_json::Value> = serde_json::from_str(&input).expect("decode the export container");
    assert!(cases.len() >= 64, "the export must contain current and rejected schemas");
    let mut accepted = 0;
    let mut rejected = 0;
    for case in cases {
        let name = case["name"].as_str().expect("case name");
        let payload = case["payload"].as_str().expect("original Kotlin payload bytes");
        let expected = case["accepted"].as_bool().expect("expected admission");
        assert_eq!(expected, create_session(payload).is_some(), "native decoder admission for {name}");
        if expected {
            let wire: serde_json::Value = serde_json::from_str(payload).expect("Kotlin payload object");
            if wire["kind"] != "google_apps_script" {
                let decoded: ripdpi_relay_core::ResolvedRelayRuntimeConfig =
                    serde_json::from_str(payload).expect("standard relay config");
                let projected = serde_json::to_value(&decoded).expect("project the decoded config");
                for key in
                    ["kind", "profileId", "server", "serverPort", "serverName", "tlsFingerprintProfile", "udpEnabled"]
                {
                    assert_eq!(wire[key], projected[key], "field {key} for {name}");
                }
                assert!(projected.get("localSocksUsername").is_none());
                assert!(projected.get("localSocksPassword").is_none());
            }
            accepted += 1;
        } else {
            rejected += 1;
        }
    }
    assert!(accepted >= 16 && rejected >= 48, "both admission paths must execute");
    println!("Kotlin relay wire: {accepted} current payloads accepted, {rejected} invalid schemas rejected");
}
