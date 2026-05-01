#[derive(Clone, Debug, PartialEq, Eq)]
pub enum FatHeaderStatus {
    Success,
    ThresholdCutoff,
    FreezeAfterThreshold,
    Reset,
    Timeout,
    ConnectFailed,
    HandshakeFailed,
}

#[derive(Clone, Debug)]
pub struct FatHeaderObservation {
    pub status: FatHeaderStatus,
    pub bytes_sent: usize,
    pub responses_seen: usize,
    pub error: Option<String>,
    /// Time from SYN to SYN-ACK (TCP connect latency), establishing baseline RTT.
    pub syn_ack_latency_ms: Option<u64>,
    /// Time from the last successful I/O (SYN-ACK or last response) to the RST/error.
    /// Compared against `syn_ack_latency_ms` to classify in-path vs server RST.
    pub rst_timing_ms: Option<u64>,
    /// TCP window size observed from the server, if available (for window-cap detection).
    pub observed_window_size: Option<u32>,
}

impl FatHeaderObservation {
    pub(super) fn connect_failed(error: String) -> Self {
        Self::failed(FatHeaderStatus::ConnectFailed, error, None, None, 0, 0)
    }

    pub(super) fn open_failed(status: FatHeaderStatus, error: String) -> Self {
        Self::failed(status, error, None, None, 0, 0)
    }

    pub(super) fn io_failed(
        status: FatHeaderStatus,
        error: String,
        syn_ack_latency_ms: Option<u64>,
        rst_timing_ms: Option<u64>,
        bytes_sent: usize,
        responses_seen: usize,
    ) -> Self {
        Self::failed(status, error, syn_ack_latency_ms, rst_timing_ms, bytes_sent, responses_seen)
    }

    pub(super) fn success(bytes_sent: usize, responses_seen: usize, syn_ack_latency_ms: Option<u64>) -> Self {
        Self {
            status: FatHeaderStatus::Success,
            bytes_sent,
            responses_seen,
            error: None,
            syn_ack_latency_ms,
            rst_timing_ms: None,
            observed_window_size: None,
        }
    }

    fn failed(
        status: FatHeaderStatus,
        error: String,
        syn_ack_latency_ms: Option<u64>,
        rst_timing_ms: Option<u64>,
        bytes_sent: usize,
        responses_seen: usize,
    ) -> Self {
        Self {
            status,
            bytes_sent,
            responses_seen,
            error: Some(error),
            syn_ack_latency_ms,
            rst_timing_ms,
            observed_window_size: None,
        }
    }
}
