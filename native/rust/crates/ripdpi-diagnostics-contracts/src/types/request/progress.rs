use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ScanProgress {
    pub session_id: String,
    pub phase: String,
    pub completed_steps: usize,
    pub total_steps: usize,
    pub message: String,
    pub is_finished: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub latest_probe_target: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub latest_probe_outcome: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub strategy_probe_progress: Option<StrategyProbeLiveProgress>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub transfer_progress: Option<TransferProgress>,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum StrategyProbeProgressLane {
    Tcp,
    Quic,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct StrategyProbeLiveProgress {
    pub lane: StrategyProbeProgressLane,
    pub candidate_index: usize,
    pub candidate_total: usize,
    pub candidate_id: String,
    pub candidate_label: String,
    #[serde(default)]
    pub succeeded_targets: usize,
    #[serde(default)]
    pub total_targets: usize,
}

/// Numeric transfer observations; no body or headers are retained.
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TransferProgress {
    pub target: String,
    pub measurement: TransferMeasurement,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TransferMeasurement {
    pub run_index: usize,
    pub run_count: usize,
    pub received_body_byte_count: u64,
    pub expected_body_byte_count: Option<u64>,
    pub elapsed_ms: u64,
    pub first_body_byte_ms: Option<u64>,
    pub last_body_progress_ms: Option<u64>,
    pub termination_reason: Option<String>,
    pub response_complete: bool,
    pub window_complete: bool,
    pub samples: Vec<TransferSample>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TransferSample {
    pub elapsed_ms: u64,
    pub body_byte_count: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TransferEvidence {
    pub version: u32,
    pub runs: Vec<TransferMeasurement>,
}

impl TransferEvidence {
    /// Encode the bounded numeric evidence without payload bytes or target identifiers.
    pub fn to_json(&self) -> Result<String, serde_json::Error> {
        serde_json::to_string(self)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn transfer_progress_round_trip_keeps_unknown_values_and_numeric_evidence() {
        let measurement = TransferMeasurement {
            run_index: 1,
            run_count: 2,
            received_body_byte_count: 3,
            expected_body_byte_count: None,
            elapsed_ms: 40,
            first_body_byte_ms: Some(10),
            last_body_progress_ms: Some(20),
            termination_reason: Some("reset".to_string()),
            response_complete: false,
            window_complete: false,
            samples: vec![
                TransferSample { elapsed_ms: 0, body_byte_count: 0 },
                TransferSample { elapsed_ms: 40, body_byte_count: 3 },
            ],
        };
        let progress = TransferProgress { target: "Fixture".to_string(), measurement: measurement.clone() };
        let json = serde_json::to_string(&progress).expect("encode");
        assert_eq!(serde_json::from_str::<TransferProgress>(&json).expect("decode"), progress);
        let evidence = TransferEvidence { version: 1, runs: vec![measurement] };
        let json: serde_json::Value =
            serde_json::from_str(&evidence.to_json().expect("encode evidence")).expect("json");
        assert!(json["runs"][0]["expectedBodyByteCount"].is_null());
        assert_eq!(json["runs"][0]["receivedBodyByteCount"], 3);
        assert!(!json.to_string().contains("Fixture"));
    }
}
