use std::net::SocketAddr;

/// Result of strategy-context preferred-target resolution.
#[derive(Debug, Default)]
pub struct PreferredTargets {
    pub targets: Vec<SocketAddr>,
    pub suppressed_targets: Vec<SocketAddr>,
    pub suppressed_udp: bool,
}
