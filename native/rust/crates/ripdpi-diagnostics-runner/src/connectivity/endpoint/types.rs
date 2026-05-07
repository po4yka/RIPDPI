use crate::connectivity::adapters::transport::{RouteExperimentReport, TargetAddress};

#[derive(Clone)]
pub(crate) struct ThroughputSample {
    pub(crate) status: String,
    pub(crate) bytes_read: usize,
    pub(crate) bps: u64,
    pub(crate) error: String,
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
