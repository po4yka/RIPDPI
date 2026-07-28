use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ScanPathMode {
    RawPath,
    InPath,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ScanKind {
    Connectivity,
    StrategyProbe,
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ScanCompletionKind {
    #[default]
    Normal,
    PartialResults,
    Terminated,
}

impl ScanCompletionKind {
    pub(crate) fn is_normal(&self) -> bool {
        matches!(self, Self::Normal)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ScanTerminationReason {
    NetworkUnavailable,
    UserCancelled,
    DeadlineExceeded,
    EngineError,
    WorkerPanicked,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum DiagnosticProfileFamily {
    General,
    WebConnectivity,
    Messaging,
    Circumvention,
    Throttling,
    DpiFull,
    AutomaticProbing,
    AutomaticAudit,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ProbeTaskFamily {
    Dns,
    Web,
    Quic,
    Tcp,
    Service,
    Circumvention,
    Telegram,
    Throughput,
    /// DoH-JSON resolver survey — the vendor JSON DoH APIs, probed
    /// independently of the wire DoH path. Serializes as `DOH_JSON_SURVEY`.
    DohJsonSurvey,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct ProbeTask {
    pub family: ProbeTaskFamily,
    pub target_id: String,
    pub label: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum ObservationKind {
    Dns,
    Domain,
    Tcp,
    Quic,
    Service,
    Circumvention,
    Telegram,
    Throughput,
    Strategy,
    ConnectionConcurrency,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TransportFailureKind {
    None,
    Timeout,
    Reset,
    Close,
    Alert,
    Certificate,
    Other,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct NativeSessionEvent {
    pub source: String,
    pub level: String,
    pub message: String,
    pub created_at: u64,
    #[serde(default)]
    pub runtime_id: Option<String>,
    #[serde(default)]
    pub mode: Option<String>,
    #[serde(default)]
    pub policy_signature: Option<String>,
    #[serde(default)]
    pub fingerprint_hash: Option<String>,
    #[serde(default)]
    pub subsystem: Option<String>,
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn scan_path_mode_serializes_screaming_snake() {
        let json = serde_json::to_string(&ScanPathMode::RawPath).expect("serialize");
        assert_eq!(json, "\"RAW_PATH\"");
    }
}
