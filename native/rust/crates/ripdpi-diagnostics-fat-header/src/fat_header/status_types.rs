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
    pub bytes_sent: usize,
    pub responses_seen: usize,
    pub error: Option<String>,
    pub failure_stage: Option<&'static str>,
    /// Time to open the transport, including proxy negotiation when used.
    pub transport_open_ms: Option<u64>,
    /// Time since the last successful I/O before an observed connection reset.
    pub rst_timing_ms: Option<u64>,
}

impl FatHeaderObservation {
    pub(super) fn open_failed(status: FatHeaderStatus, error: String) -> Self {
        Self::failed(status, error, None, None, 0, 0)
    }

    pub(super) fn io_failed(
        status: FatHeaderStatus,
        error: String,
        transport_open_ms: Option<u64>,
        rst_timing_ms: Option<u64>,
        bytes_sent: usize,
        responses_seen: usize,
    ) -> Self {
        Self::failed(status, error, transport_open_ms, rst_timing_ms, bytes_sent, responses_seen)
    }

    pub(super) fn success(bytes_sent: usize, responses_seen: usize, transport_open_ms: Option<u64>) -> Self {
        Self {
            status: FatHeaderStatus::Success,
            bytes_sent,
            responses_seen,
            error: None,
            transport_open_ms,
            rst_timing_ms: None,
            failure_stage: None,
        }
    }

    fn failed(
        status: FatHeaderStatus,
        error: String,
        transport_open_ms: Option<u64>,
        rst_timing_ms: Option<u64>,
        bytes_sent: usize,
        responses_seen: usize,
    ) -> Self {
        Self {
            status,
            bytes_sent,
            responses_seen,
            error: Some(error),
            transport_open_ms,
            rst_timing_ms,
            failure_stage: None,
        }
    }
}
