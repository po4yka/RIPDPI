use golden_test_support::repo_root;
use serde_json::Value;

#[test]
fn pluggable_transport_manifest_sources_webtunnel_from_rust_crate() {
    // The manifest lives outside `native/rust/`, so cargo-mutants' temporary
    // workspace copy cannot reach it via a relative path; resolve it through
    // the repo root (RIPDPI_REPO_ROOT-aware) instead.
    let manifest_path = repo_root().join("native/pluggable-transports/sources.json");
    let manifest: Value =
        serde_json::from_str(&std::fs::read_to_string(&manifest_path).expect("read PT sources manifest"))
            .expect("parse PT sources manifest");
    let sources = manifest["sources"].as_array().expect("manifest sources");

    let lyrebird = sources
        .iter()
        .find(|source| source["id"] == "lyrebird")
        .expect("lyrebird source remains for snowflake and obfs4");
    let lyrebird_outputs = lyrebird["outputNames"].as_array().expect("lyrebird outputNames");
    assert!(
        !lyrebird_outputs.iter().any(|output| output == "ripdpi-webtunnel"),
        "ripdpi-webtunnel must not be emitted from the Go lyrebird source",
    );

    let webtunnel = sources.iter().find(|source| source["id"] == "ripdpi-webtunnel").expect("ripdpi-webtunnel source");
    assert_eq!(webtunnel["sourceType"], "rust");
    assert_eq!(webtunnel["packageName"], "ripdpi-webtunnel");
    assert_eq!(webtunnel["sourceBinaryName"], "ripdpi-webtunnel");
    assert_eq!(webtunnel["outputNames"], serde_json::json!(["ripdpi-webtunnel"]));
}
