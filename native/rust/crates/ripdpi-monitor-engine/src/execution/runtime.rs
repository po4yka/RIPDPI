mod adaptive_freeze;
mod contracts;
mod launch;
mod preparation;
mod timeout;
mod unavailable;
mod warmup;

use std::time::Duration;

#[cfg(test)]
pub(crate) use adaptive_freeze::freeze_adaptive_fake_ttl_for_probe;
pub use contracts::{CandidateProbeRuntime, CandidateRuntimeError, CandidateRuntimeLauncher, PreparedCandidateRuntime};
pub use launch::probe_runtime_transport;
pub(crate) use launch::probe_tcp_runtime_transport;
pub use unavailable::UnavailableCandidateRuntimeLauncher;
pub use warmup::run_candidate_warmup;

/// Compute adaptive connect timeout based on observed control RTT.
/// Uses max(MIN_ADAPTIVE_TIMEOUT, control_rtt * RTT_MULTIPLIER) capped at CONNECT_TIMEOUT.
/// Currently a building block for future per-candidate timeout tuning.
#[allow(dead_code)]
pub fn adaptive_connect_timeout(control_rtt_ms: Option<u64>) -> Duration {
    timeout::adaptive_connect_timeout(control_rtt_ms)
}

#[cfg(test)]
mod tests {
    use ripdpi_monitor_adapter::proxy_config::ProxyUiConfig;

    use super::*;
    use crate::transport::TransportConfig;

    #[test]
    fn freeze_adaptive_fake_ttl_clamps_fallback_to_range() {
        let mut config = test_ui_config();
        config.fake_packets.fake_ttl = 11;
        config.fake_packets.adaptive_fake_ttl_enabled = true;
        config.fake_packets.adaptive_fake_ttl_min = 3;
        config.fake_packets.adaptive_fake_ttl_max = 9;
        config.fake_packets.adaptive_fake_ttl_fallback = 13;

        freeze_adaptive_fake_ttl_for_probe(&mut config);

        assert_eq!(config.fake_packets.fake_ttl, 9);
        assert!(!config.fake_packets.adaptive_fake_ttl_enabled);
    }

    #[test]
    fn freeze_adaptive_fake_ttl_uses_fake_ttl_when_fallback_is_zero() {
        let mut config = test_ui_config();
        config.fake_packets.fake_ttl = 7;
        config.fake_packets.adaptive_fake_ttl_enabled = true;
        config.fake_packets.adaptive_fake_ttl_min = 3;
        config.fake_packets.adaptive_fake_ttl_max = 12;
        config.fake_packets.adaptive_fake_ttl_fallback = 0;

        freeze_adaptive_fake_ttl_for_probe(&mut config);

        assert_eq!(config.fake_packets.fake_ttl, 7);
        assert!(!config.fake_packets.adaptive_fake_ttl_enabled);
    }

    #[test]
    fn freeze_adaptive_fake_ttl_noop_when_disabled() {
        let mut config = test_ui_config();
        config.fake_packets.fake_ttl = 8;
        config.fake_packets.adaptive_fake_ttl_enabled = false;

        freeze_adaptive_fake_ttl_for_probe(&mut config);

        assert_eq!(config.fake_packets.fake_ttl, 8);
    }

    fn test_ui_config() -> ProxyUiConfig {
        let mut config = ProxyUiConfig::default();
        config.protocols.desync_udp = true;
        config.chains.tcp_steps = vec![];
        config.fake_packets.fake_sni = "www.wikipedia.org".to_string();
        config
    }

    struct FakeProbeRuntime {
        transport: TransportConfig,
    }

    impl CandidateProbeRuntime for FakeProbeRuntime {
        fn transport(&self) -> TransportConfig {
            self.transport.clone()
        }
    }

    struct FakeRuntimeLauncher;

    impl CandidateRuntimeLauncher for FakeRuntimeLauncher {
        fn start_candidate_runtime(
            &self,
            prepared: PreparedCandidateRuntime,
        ) -> Result<Box<dyn CandidateProbeRuntime>, CandidateRuntimeError> {
            assert_eq!(prepared.config.network.listen.listen_ip.to_string(), "127.0.0.1");
            assert_eq!(prepared.config.network.listen.listen_port, 0);
            Ok(Box::new(FakeProbeRuntime {
                transport: TransportConfig::Socks5 { host: "127.0.0.1".to_string(), port: 10_800 },
            }))
        }
    }

    #[test]
    fn probe_runtime_transport_uses_candidate_runtime_launcher_boundary() {
        let spec = crate::candidates::candidate_spec("test", "Test", "test", test_ui_config());
        let runtime = probe_runtime_transport(&FakeRuntimeLauncher, &spec, None).expect("fake launcher should start");

        let TransportConfig::Socks5 { host, port } = runtime.transport() else {
            panic!("fake launcher should expose SOCKS5 transport");
        };
        assert_eq!(host, "127.0.0.1");
        assert_eq!(port, 10_800);
    }

    #[test]
    fn probe_runtime_transport_accepts_injected_direct_launcher() {
        let launcher = DirectRuntimeLauncher;
        let spec = crate::candidates::candidate_spec("test", "Test", "test", test_ui_config());
        let runtime =
            probe_runtime_transport(&launcher, &spec, None).expect("probe runtime should start with direct transport");
        let TransportConfig::Direct { .. } = runtime.transport() else {
            panic!("probe runtime should expose direct transport");
        };
    }

    #[test]
    fn probe_runtime_transport_overrides_listen_ip_to_localhost() {
        let launcher = AssertingPreparedRuntimeLauncher;
        let mut config = test_ui_config();
        config.listen.ip = "0.0.0.0".to_string();
        let spec = crate::candidates::candidate_spec("test", "Test", "test", config);
        let _runtime = probe_runtime_transport(&launcher, &spec, None).expect("probe runtime should start");
    }

    struct DirectRuntimeLauncher;

    impl CandidateRuntimeLauncher for DirectRuntimeLauncher {
        fn start_candidate_runtime(
            &self,
            _prepared: PreparedCandidateRuntime,
        ) -> Result<Box<dyn CandidateProbeRuntime>, CandidateRuntimeError> {
            Ok(Box::new(FakeProbeRuntime { transport: TransportConfig::Direct { route_experiment: None } }))
        }
    }

    struct AssertingPreparedRuntimeLauncher;

    impl CandidateRuntimeLauncher for AssertingPreparedRuntimeLauncher {
        fn start_candidate_runtime(
            &self,
            prepared: PreparedCandidateRuntime,
        ) -> Result<Box<dyn CandidateProbeRuntime>, CandidateRuntimeError> {
            assert_eq!(prepared.config.network.listen.listen_ip.to_string(), "127.0.0.1");
            assert_eq!(prepared.config.network.listen.listen_port, 0);
            Ok(Box::new(FakeProbeRuntime { transport: TransportConfig::Direct { route_experiment: None } }))
        }
    }

    struct TcpOnlyRelayRuntimeLauncher {
        expect_relay: bool,
    }

    impl CandidateRuntimeLauncher for TcpOnlyRelayRuntimeLauncher {
        fn start_candidate_runtime(
            &self,
            prepared: PreparedCandidateRuntime,
        ) -> Result<Box<dyn CandidateProbeRuntime>, CandidateRuntimeError> {
            assert!(!prepared.config.network.udp, "TCP probe runtime must disable UDP ASSOCIATE");
            if self.expect_relay {
                assert!(
                    prepared.config.groups.iter().all(|group| group.policy.ext_socks.is_some()),
                    "TCP probe runtime must preserve the configured relay path"
                );
            }
            Ok(Box::new(FakeProbeRuntime { transport: TransportConfig::Direct { route_experiment: None } }))
        }
    }

    #[test]
    fn tcp_probe_runtime_accepts_every_full_matrix_candidate_with_a_tcp_only_relay() {
        let mut base = test_ui_config();
        base.protocols.udp_associate_enabled = None;
        base.upstream_relay.enabled = true;
        base.upstream_relay.kind = "vless_reality".to_string();
        base.upstream_relay.udp_enabled = false;
        let suite = crate::candidates::build_strategy_probe_suite("full_matrix_v1", &base)
            .expect("full matrix candidate suite");

        for spec in &suite.tcp_candidates {
            let launcher = TcpOnlyRelayRuntimeLauncher { expect_relay: spec.config.upstream_relay.enabled };
            probe_tcp_runtime_transport(&launcher, spec, None)
                .unwrap_or_else(|error| panic!("{} must reach the TCP probe launcher: {error}", spec.id));
            assert_eq!(
                spec.config.protocols.udp_associate_enabled, None,
                "{} recommendation config must not inherit the probe-only override",
                spec.id
            );
        }
    }

    #[test]
    fn unavailable_candidate_runtime_launcher_returns_actionable_error() {
        let launcher = UnavailableCandidateRuntimeLauncher;
        let spec = crate::candidates::candidate_spec("test", "Test", "test", test_ui_config());
        let Err(err) = probe_runtime_transport(&launcher, &spec, None) else {
            panic!("launcher should be unavailable");
        };
        assert_eq!(err, CandidateRuntimeError::LauncherUnavailable);
        assert_eq!(err.to_string(), "candidate runtime launcher is not configured");
    }

    #[test]
    fn fake_runtime_launcher_can_return_socks5_transport() {
        let launcher = FakeRuntimeLauncher;
        let spec = crate::candidates::candidate_spec("test", "Test", "test", test_ui_config());
        let runtime = probe_runtime_transport(&launcher, &spec, None).expect("fake launcher should start");
        let TransportConfig::Socks5 { host, .. } = runtime.transport() else {
            panic!("probe runtime should expose SOCKS5 transport");
        };
        assert_eq!(host, "127.0.0.1");
    }
}
