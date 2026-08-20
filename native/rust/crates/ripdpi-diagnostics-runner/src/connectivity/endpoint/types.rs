use crate::connectivity::adapters::transport::{RouteExperimentReport, TargetAddress};

#[derive(Clone, Debug)]
pub(crate) struct ThroughputSample {
    pub(crate) status: String,
    pub(crate) bytes_read: usize,
    pub(crate) bps: u64,
    pub(crate) error: String,
    pub(crate) failure_stage: String,
    pub(crate) failure_class: String,
    pub(crate) duration_ms: u64,
    pub(crate) http_status_code: Option<u16>,
    pub(crate) negotiated_alpn: Option<String>,
    pub(crate) address_family: Option<String>,
    pub(crate) tcp_connect_ms: Option<u64>,
    pub(crate) tls_handshake_ms: Option<u64>,
    pub(crate) read_completion: String,
    pub(crate) retry_attempt: u8,
}

impl ThroughputSample {
    pub(crate) fn completed_window(&self) -> bool {
        self.read_completion == "window_complete"
    }
}

pub(crate) struct EndpointProbeObservation {
    pub(crate) status: String,
    pub(crate) error: String,
    pub(crate) local_addr: Option<std::net::SocketAddr>,
    pub(crate) route_report: Option<RouteExperimentReport>,
}

pub(super) struct ParsedHttpTarget {
    pub(super) host: String,
    pub(super) path: String,
    pub(super) port: u16,
    pub(super) secure: bool,
    pub(super) connect_targets: Vec<TargetAddress>,
}
