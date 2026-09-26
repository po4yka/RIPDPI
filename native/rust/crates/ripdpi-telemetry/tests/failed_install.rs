use ripdpi_telemetry::recorder::{install, snapshot};

#[test]
fn occupied_global_recorder_is_reported_without_empty_snapshot() {
    metrics::set_global_recorder(metrics::NoopRecorder).expect("install competing recorder");
    assert!(!install());
    assert!(!install());
    assert!(snapshot().is_none());
}
