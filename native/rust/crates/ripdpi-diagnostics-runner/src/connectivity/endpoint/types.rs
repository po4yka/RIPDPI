use crate::connectivity::adapters::transport::{RouteExperimentReport, TargetAddress};

#[derive(Clone, Debug)]
pub(crate) struct ThroughputSample {
    pub(crate) measurement: Option<crate::types::TransferMeasurement>,
    pub(crate) verification_outcome: Option<&'static str>,
    pub(crate) status: String,
    pub(crate) bytes_read: usize,
    pub(crate) bps: u64,
    pub(crate) body_goodput_bps: Option<u64>,
    pub(crate) error: String,
    pub(crate) failure_stage: String,
    pub(crate) failure_class: String,
    pub(crate) duration_ms: u64,
    pub(crate) http_status_code: Option<u16>,
    pub(crate) negotiated_alpn: Option<String>,
    pub(crate) address_family: Option<String>,
    pub(crate) transport_open_ms: Option<u64>,
    pub(crate) tls_handshake_ms: Option<u64>,
    pub(crate) read_completion: String,
    pub(crate) retry_attempt: u8,
}

impl ThroughputSample {
    pub(crate) fn accepted_response(&self) -> bool {
        self.http_status_code.is_some_and(|code| (200..300).contains(&code) && code != 206)
            && self.status != "http_blockpage"
    }

    pub(crate) fn accepted_window(&self) -> bool {
        self.accepted_response() && self.read_completion == "window_complete"
    }
}

pub(crate) struct EndpointProbeObservation {
    pub(crate) status: String,
    pub(crate) error: String,
    pub(crate) local_addr: Option<std::net::SocketAddr>,
    pub(crate) route_report: Option<RouteExperimentReport>,
}

impl EndpointProbeObservation {
    pub(super) fn without_connection(status: &str, error: &str) -> Self {
        Self { status: status.into(), error: error.into(), local_addr: None, route_report: None }
    }
}

pub(super) struct ParsedHttpTarget {
    pub(super) host: String,
    pub(super) path: String,
    pub(super) port: u16,
    pub(super) secure: bool,
    pub(super) connect_targets: Vec<TargetAddress>,
}
