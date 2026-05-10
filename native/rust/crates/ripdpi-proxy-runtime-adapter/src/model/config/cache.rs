use ripdpi_config::{QuicInitialMode, RuntimeConfig};
use ripdpi_runtime_decision_ports::{ExtractedHost, HostSource};

pub fn quic_route_and_cache_enabled(config: &RuntimeConfig) -> bool {
    matches!(config.quic.initial_mode, QuicInitialMode::RouteAndCache)
}

pub fn should_cache_udp_host(config: &RuntimeConfig, host: Option<&ExtractedHost>) -> bool {
    match host.map(|value| value.source) {
        Some(HostSource::Quic) => quic_route_and_cache_enabled(config),
        Some(HostSource::Http | HostSource::Tls) => true,
        None => false,
    }
}

#[cfg(test)]
mod tests {
    use ripdpi_config::{QuicInitialMode, RuntimeConfig};
    use ripdpi_runtime_decision_ports::{ExtractedHost, HostSource};

    use super::*;

    #[test]
    fn udp_host_cache_policy_requires_quic_route_cache_mode_for_quic_hosts() {
        let mut config = RuntimeConfig::default();
        config.quic.initial_mode = QuicInitialMode::Disabled;
        let quic_host = ExtractedHost { host: "example.com".to_string(), source: HostSource::Quic };
        let tls_host = ExtractedHost { host: "example.com".to_string(), source: HostSource::Tls };

        assert!(!should_cache_udp_host(&config, Some(&quic_host)));
        assert!(should_cache_udp_host(&config, Some(&tls_host)));
        assert!(!should_cache_udp_host(&config, None));

        config.quic.initial_mode = QuicInitialMode::RouteAndCache;

        assert!(should_cache_udp_host(&config, Some(&quic_host)));
    }
}
