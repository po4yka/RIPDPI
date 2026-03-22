use std::fs;
use std::path::{Path, PathBuf};

use ripdpi_monitor::{
    EngineProgressWire, EngineScanReportWire, EngineScanRequestWire, DIAGNOSTICS_ENGINE_SCHEMA_VERSION,
};
use serde_json::Value;

fn repo_root() -> PathBuf {
    Path::new(env!("CARGO_MANIFEST_DIR")).join("../../../../").canonicalize().expect("repo root")
}

fn fixture(path: &str) -> String {
    fs::read_to_string(repo_root().join(path)).expect("fixture")
}

#[test]
fn shared_contract_fixtures_decode_successfully() {
    let request: EngineScanRequestWire =
        serde_json::from_str(&fixture("diagnostics-contract-fixtures/engine_request_current.json"))
            .expect("engine request");
    let report: EngineScanReportWire =
        serde_json::from_str(&fixture("diagnostics-contract-fixtures/engine_report_current.json"))
            .expect("engine report");
    let progress: EngineProgressWire =
        serde_json::from_str(&fixture("diagnostics-contract-fixtures/engine_progress_current.json"))
            .expect("engine progress");
    let profile_catalog: Value =
        serde_json::from_str(&fixture("diagnostics-contract-fixtures/profile_catalog_current.json"))
            .expect("profile catalog");

    assert_eq!(request.schema_version, DIAGNOSTICS_ENGINE_SCHEMA_VERSION);
    assert_eq!(report.schema_version, DIAGNOSTICS_ENGINE_SCHEMA_VERSION);
    assert_eq!(progress.schema_version, DIAGNOSTICS_ENGINE_SCHEMA_VERSION);
    assert_eq!(profile_catalog["schemaVersion"].as_u64(), Some(2));
}

#[test]
fn engine_schema_version_matches_kotlin_contract_constant() {
    let kotlin_engine_contract =
        fixture("core/diagnostics/src/main/java/com/poyka/ripdpi/diagnostics/contract/engine/EngineContract.kt");
    let kotlin_version = kotlin_engine_contract
        .lines()
        .find_map(|line| {
            line.split('=').nth(1).map(str::trim).filter(|_| line.contains("DiagnosticsEngineSchemaVersion"))
        })
        .expect("kotlin schema version")
        .parse::<u32>()
        .expect("schema version");

    assert_eq!(kotlin_version, DIAGNOSTICS_ENGINE_SCHEMA_VERSION);
}
