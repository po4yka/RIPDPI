#[derive(Clone, Default)]
pub struct Hysteria2RelayConfig {
    pub password: Option<String>,
    pub salamander_key: Option<String>,
    /// Skip TLS certificate verification (`insecure=1` on the share link).
    pub insecure: bool,
    /// SHA-256 pin of the server leaf certificate, as hex (`pinSHA256` on the
    /// share link). With `insecure`, the pin replaces CA validation; without
    /// it, both apply. A malformed value fails the backend build.
    pub pin_sha256: Option<String>,
    pub port_hop_ports: Option<String>,
    pub port_hop_interval_millis: Option<u64>,
    pub port_hop_interval_max_millis: Option<u64>,
}

impl_redacted_debug!(Hysteria2RelayConfig { insecure, pin_sha256 });
