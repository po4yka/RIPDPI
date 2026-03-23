use std::collections::BTreeSet;
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
    let outcome_taxonomy: Value =
        serde_json::from_str(&fixture("diagnostics-contract-fixtures/outcome_taxonomy_current.json"))
            .expect("outcome taxonomy");

    assert_eq!(request.schema_version, DIAGNOSTICS_ENGINE_SCHEMA_VERSION);
    assert_eq!(report.schema_version, DIAGNOSTICS_ENGINE_SCHEMA_VERSION);
    assert_eq!(progress.schema_version, DIAGNOSTICS_ENGINE_SCHEMA_VERSION);
    assert_eq!(profile_catalog["schemaVersion"].as_u64(), Some(2));
    assert_eq!(outcome_taxonomy["schemaVersion"].as_u64(), Some(1));
}

#[test]
fn engine_schema_version_matches_kotlin_contract_constant() {
    let kotlin_engine_contract =
        fixture("core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/contract/engine/EngineContract.kt");
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

#[test]
fn outcome_fixture_covers_emitted_native_outcome_tokens() {
    let fixture_tokens = outcome_fixture_tokens("diagnostics-contract-fixtures/outcome_taxonomy_current.json");
    let emitted_tokens = emitted_native_outcome_tokens();

    assert_eq!(fixture_tokens, emitted_tokens);
}

fn outcome_fixture_tokens(path: &str) -> BTreeSet<String> {
    let value: Value = serde_json::from_str(&fixture(path)).expect("fixture json");
    value["outcomes"]
        .as_array()
        .expect("outcomes array")
        .iter()
        .filter_map(|entry| entry["outcome"].as_str().map(str::to_string))
        .collect()
}

fn emitted_native_outcome_tokens() -> BTreeSet<String> {
    let repo = repo_root();
    let mut tokens = BTreeSet::new();
    for (path, signature) in [
        ("native/rust/crates/ripdpi-monitor/src/connectivity.rs", "pub(crate) fn run_dns_probe"),
        ("native/rust/crates/ripdpi-monitor/src/connectivity.rs", "pub(crate) fn run_domain_probe"),
        ("native/rust/crates/ripdpi-monitor/src/connectivity.rs", "pub(crate) fn run_tcp_probe"),
        ("native/rust/crates/ripdpi-monitor/src/connectivity.rs", "pub(crate) fn run_quic_probe"),
        ("native/rust/crates/ripdpi-monitor/src/connectivity.rs", "pub(crate) fn run_service_probe"),
        ("native/rust/crates/ripdpi-monitor/src/connectivity.rs", "pub(crate) fn run_circumvention_probe"),
        ("native/rust/crates/ripdpi-monitor/src/connectivity.rs", "pub(crate) fn run_throughput_probe"),
        ("native/rust/crates/ripdpi-monitor/src/connectivity.rs", "pub(crate) fn build_network_environment_probe"),
        ("native/rust/crates/ripdpi-monitor/src/execution.rs", "pub(crate) fn run_http_strategy_probe"),
        ("native/rust/crates/ripdpi-monitor/src/execution.rs", "pub(crate) fn run_https_strategy_probe"),
        ("native/rust/crates/ripdpi-monitor/src/execution.rs", "pub(crate) fn run_quic_strategy_probe"),
        ("native/rust/crates/ripdpi-monitor/src/telegram.rs", "pub(crate) fn run_telegram_probe"),
        ("native/rust/crates/ripdpi-monitor/src/fat_header.rs", "pub(crate) fn classify_fat_header_outcome"),
    ] {
        tokens.extend(quoted_outcome_tokens(&function_body(&repo.join(path), signature)));
    }
    tokens.extend(quoted_outcome_tokens(&section(
        &repo.join("native/rust/crates/ripdpi-failure-classifier/src/lib.rs"),
        "impl FailureClass",
        "impl FailureStage",
    )));
    tokens
}

fn function_body(path: &Path, signature: &str) -> String {
    let source = fs::read_to_string(path).expect("source");
    let start = source.find(signature).expect("function signature");
    let brace_start = source[start..].find('{').map(|offset| start + offset).expect("opening brace");
    let mut depth = 0usize;
    for (offset, ch) in source[brace_start..].char_indices() {
        match ch {
            '{' => depth += 1,
            '}' => {
                depth -= 1;
                if depth == 0 {
                    return source[brace_start..=brace_start + offset].to_string();
                }
            }
            _ => {}
        }
    }
    panic!("unclosed function body for {signature}");
}

fn section(path: &Path, start_marker: &str, end_marker: &str) -> String {
    let source = fs::read_to_string(path).expect("source");
    let start = source.find(start_marker).expect("section start");
    let end = source[start..].find(end_marker).map(|offset| start + offset).expect("section end");
    source[start..end].to_string()
}

fn quoted_outcome_tokens(segment: &str) -> BTreeSet<String> {
    let mut tokens = BTreeSet::new();
    let mut chars = segment.char_indices();
    while let Some((start, ch)) = chars.next() {
        if ch != '"' {
            continue;
        }
        let mut end = None;
        for (index, next) in chars.by_ref() {
            if next == '"' {
                end = Some(index);
                break;
            }
        }
        let Some(end) = end else {
            break;
        };
        let candidate = &segment[start + 1..end];
        if is_outcome_token(candidate) {
            tokens.insert(candidate.to_string());
        }
    }
    tokens
}

fn is_outcome_token(value: &str) -> bool {
    const EXCLUDED: &[&str] = &[
        "network_environment",
        "dns_integrity",
        "domain_reachability",
        "tcp_fat_header",
        "quic_reachability",
        "service_reachability",
        "circumvention_reachability",
        "telegram_availability",
        "throughput_window",
        "strategy_http",
        "strategy_https",
        "strategy_quic",
        "snake_case",
        "none",
        "not_run",
        "tcp_connect_ok",
    ];
    if EXCLUDED.contains(&value) {
        return false;
    }
    if matches!(value, "ok" | "slow" | "partial" | "blocked" | "error" | "unknown" | "redirect" | "unreachable") {
        return true;
    }
    !value.is_empty()
        && value.chars().all(|ch| ch.is_ascii_lowercase() || ch.is_ascii_digit() || ch == '_')
        && value.contains('_')
}
