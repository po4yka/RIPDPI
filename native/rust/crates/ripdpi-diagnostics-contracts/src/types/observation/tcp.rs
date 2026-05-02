use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "SCREAMING_SNAKE_CASE")]
pub enum TcpProbeStatus {
    Ok,
    ConnectFailed,
    Blocked16Kb,
    FreezeAfterThreshold,
    WhitelistSniOk,
    Error,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct TcpObservationFact {
    pub provider: String,
    pub status: TcpProbeStatus,
    #[serde(default)]
    pub selected_sni: Option<String>,
    #[serde(default)]
    pub bytes_sent: Option<usize>,
    #[serde(default)]
    pub responses_seen: Option<usize>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub freeze_threshold_bytes: Option<usize>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub port: Option<u16>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub alt_port: Option<u16>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub alt_port_status: Option<String>,
    /// How the TCP connection was blocked: "rst_injection", "window_cap", "timeout",
    /// "connection_refused", or "none".
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub tcp_block_method: Option<String>,
    /// Observed TCP window size from server (useful for window-cap detection).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub observed_window_size: Option<u32>,
    /// Time from the last successful I/O to the RST/error, in milliseconds.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub rst_timing_ms: Option<u64>,
    /// Time from SYN to SYN-ACK in milliseconds (baseline RTT).
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub syn_ack_latency_ms: Option<u64>,
    /// RST origin classification: "in_path_rst", "server_rst", or "unknown".
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub rst_origin: Option<String>,
}
