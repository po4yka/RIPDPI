use crate::types::{NativeSessionEvent, ScanProgress, ScanReport};
use ripdpi_diagnostics_contracts::{EngineProgressWire, EngineScanReportWire};

pub(super) fn progress_to_json(progress: Option<&ScanProgress>) -> Result<Option<String>, String> {
    progress
        .map(|progress| serde_json::to_string(&EngineProgressWire::from(progress.clone())))
        .transpose()
        .map_err(|err| err.to_string())
}

pub(super) fn report_to_json(report: Option<&ScanReport>) -> Result<Option<String>, String> {
    report
        .map(|report| serde_json::to_string(&EngineScanReportWire::from(report.clone())))
        .transpose()
        .map_err(|err| err.to_string())
}

pub(super) fn passive_events_to_json(events: Vec<NativeSessionEvent>) -> Result<Option<String>, String> {
    serde_json::to_string(&events).map(Some).map_err(|err| err.to_string())
}
