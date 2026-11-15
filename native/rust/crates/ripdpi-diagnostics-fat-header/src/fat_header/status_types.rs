#[derive(Clone, Debug, PartialEq, Eq)]
pub enum FatHeaderStatus {
    Success,
    Inconclusive,
    Reset,
    Timeout,
    ConnectFailed,
    HandshakeFailed,
}

#[derive(Clone, Debug)]
pub struct FatHeaderObservation {
    pub status: FatHeaderStatus,
    /// Application octets accepted by write operations, before TLS encoding.
    pub bytes_sent: usize,
    /// Local write count when the most recent final HEAD response was received.
    pub bytes_at_last_response: usize,
    /// A successful final response was observed after writing at least the byte boundary.
    pub threshold_covered: bool,
    pub requests_planned: usize,
    /// Requests for which the complete write and flush succeeded.
    pub requests_written: usize,
    pub responses_seen: usize,
    pub http_status: Option<u16>,
    pub error: Option<String>,
    pub failure_stage: Option<&'static str>,
    /// Time to open the transport, including proxy negotiation when used.
    pub transport_open_ms: Option<u64>,
    /// Time since the last successful I/O before an observed connection reset.
    pub rst_timing_ms: Option<u64>,
}

impl FatHeaderObservation {
    pub(super) fn pending(requests_planned: usize) -> Self {
        Self {
            status: FatHeaderStatus::Inconclusive,
            bytes_sent: 0,
            bytes_at_last_response: 0,
            threshold_covered: false,
            requests_planned,
            requests_written: 0,
            responses_seen: 0,
            http_status: None,
            error: None,
            failure_stage: None,
            transport_open_ms: None,
            rst_timing_ms: None,
        }
    }

    pub(super) fn failed(mut self, status: FatHeaderStatus, stage: &'static str, error: impl Into<String>) -> Self {
        self.status = status;
        self.error = Some(error.into());
        self.failure_stage = Some(stage);
        self
    }
}
