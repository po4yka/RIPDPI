use std::time::Duration;

/// Per-domain TCP connect + TLS handshake timeout.
pub(crate) const PROBE_TIMEOUT: Duration = Duration::from_secs(5);

/// Total deadline for the entire reprobe batch.
pub(crate) const TOTAL_DEADLINE: Duration = Duration::from_secs(20);

pub(crate) struct ProbeTarget {
    pub(crate) domain: &'static str,
    pub(crate) ip: &'static str,
}

/// Domains to probe. These are commonly DPI-blocked targets that exercise TLS
/// handshake classification. Using 3 domains keeps the total probe budget small.
pub(crate) const PROBE_TARGETS: &[ProbeTarget] = &[
    ProbeTarget { domain: "youtube.com", ip: "142.250.74.206" },
    ProbeTarget { domain: "discord.com", ip: "162.159.128.233" },
    ProbeTarget { domain: "telegram.org", ip: "149.154.167.99" },
];
