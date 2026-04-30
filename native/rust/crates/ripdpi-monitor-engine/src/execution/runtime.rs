use std::net::{SocketAddr, TcpStream};
use std::sync::Arc;
use std::thread::{self, JoinHandle};
use std::time::Duration;

use rustls::client::danger::ServerCertVerifier;

use ripdpi_config::RuntimeConfig;
use ripdpi_proxy_config::{
    runtime_config_from_ui, ProxyRuntimeContext, ProxyUiConfig, ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK,
};
use ripdpi_proxy_runtime as runtime;
use ripdpi_runtime_api::EmbeddedProxyControl;

use crate::candidates::{CandidateWarmup, StrategyCandidateSpec};
use crate::http::try_http_request;
use crate::tls::{try_tls_handshake, TlsClientProfile};
use crate::transport::{domain_connect_target, wait_for_listener, TransportConfig};
use crate::types::DomainTarget;
use crate::util::CONNECT_TIMEOUT;

pub struct TemporaryProxyRuntime {
    pub addr: SocketAddr,
    pub control: Arc<EmbeddedProxyControl>,
    pub handle: Option<JoinHandle<Result<(), String>>>,
}

impl TemporaryProxyRuntime {
    pub fn start(config: RuntimeConfig, runtime_context: Option<ProxyRuntimeContext>) -> Result<Self, String> {
        let listener = runtime::create_listener(&config).map_err(|err| err.to_string())?;
        let addr = listener.local_addr().map_err(|err| err.to_string())?;
        let control = Arc::new(EmbeddedProxyControl::new_with_context(None, runtime_context));
        let worker_control = control.clone();
        let handle = thread::spawn(move || {
            runtime::run_proxy_with_embedded_control(config, listener, worker_control).map_err(|err| err.to_string())
        });
        wait_for_listener(addr)?;
        Ok(Self { addr, control, handle: Some(handle) })
    }

    pub fn transport(&self) -> TransportConfig {
        TransportConfig::Socks5 { host: "127.0.0.1".to_string(), port: self.addr.port() }
    }
}

impl Drop for TemporaryProxyRuntime {
    fn drop(&mut self) {
        self.control.request_shutdown();
        let _ = TcpStream::connect(self.addr);
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}

/// Compute adaptive connect timeout based on observed control RTT.
/// Uses max(MIN_ADAPTIVE_TIMEOUT, control_rtt * RTT_MULTIPLIER) capped at CONNECT_TIMEOUT.
/// Currently a building block for future per-candidate timeout tuning.
#[allow(dead_code)]
pub fn adaptive_connect_timeout(control_rtt_ms: Option<u64>) -> Duration {
    const MIN_ADAPTIVE_TIMEOUT: Duration = Duration::from_millis(1500);
    const RTT_MULTIPLIER: u64 = 15;

    match control_rtt_ms {
        Some(rtt) if rtt > 0 => {
            let scaled = Duration::from_millis(rtt * RTT_MULTIPLIER);
            scaled.max(MIN_ADAPTIVE_TIMEOUT).min(CONNECT_TIMEOUT)
        }
        _ => CONNECT_TIMEOUT,
    }
}

pub fn probe_runtime_transport(
    spec: &StrategyCandidateSpec,
    runtime_context: Option<&ProxyRuntimeContext>,
) -> Result<TemporaryProxyRuntime, String> {
    let mut runtime_config = spec.config.clone();
    runtime_config.listen.ip = "127.0.0.1".to_string();
    runtime_config.host_autolearn.enabled = false;
    runtime_config.host_autolearn.store_path = None;
    if !spec.preserve_adaptive_fake_ttl {
        freeze_adaptive_fake_ttl_for_probe(&mut runtime_config);
    }
    let mut config = runtime_config_from_ui(runtime_config).map_err(|err| {
        tracing::warn!(candidate = spec.id, error = %err, "probe runtime config validation failed");
        err.to_string()
    })?;
    let _ = ripdpi_proxy_config::presets::apply_runtime_preset("ripdpi_default", &mut config);
    config.network.listen.listen_port = 0;
    if let Some(ctx) = runtime_context {
        if let Some(ref path) = ctx.protect_path {
            config.process.protect_path = Some(path.clone());
        }
    }
    match TemporaryProxyRuntime::start(config, runtime_context.cloned()) {
        Ok(runtime) => {
            tracing::debug!(candidate = spec.id, addr = %runtime.addr, "probe runtime started");
            Ok(runtime)
        }
        Err(err) => {
            tracing::warn!(candidate = spec.id, error = %err, "probe runtime failed to start");
            Err(err)
        }
    }
}

pub fn run_candidate_warmup(
    spec: &StrategyCandidateSpec,
    transport: &TransportConfig,
    targets: &[DomainTarget],
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) {
    if spec.warmup != CandidateWarmup::AdaptiveFakeTtl {
        return;
    }
    for target in targets {
        let http_port = target.http_port.unwrap_or(80);
        let https_port = target.https_port.unwrap_or(443);
        let _ = try_http_request(
            &domain_connect_target(target),
            http_port,
            transport,
            &target.host,
            &target.http_path,
            false,
        );
        let _ = try_tls_handshake(
            &domain_connect_target(target),
            https_port,
            transport,
            &target.host,
            true,
            TlsClientProfile::Tls13Only,
            tls_verifier,
        );
    }
}

pub fn freeze_adaptive_fake_ttl_for_probe(runtime_config: &mut ProxyUiConfig) {
    if !runtime_config.fake_packets.adaptive_fake_ttl_enabled {
        return;
    }
    let min_ttl = runtime_config.fake_packets.adaptive_fake_ttl_min.clamp(1, 255);
    let max_ttl = runtime_config.fake_packets.adaptive_fake_ttl_max.clamp(min_ttl, 255);
    let fallback = if runtime_config.fake_packets.adaptive_fake_ttl_fallback > 0 {
        runtime_config.fake_packets.adaptive_fake_ttl_fallback
    } else if runtime_config.fake_packets.fake_ttl > 0 {
        runtime_config.fake_packets.fake_ttl
    } else {
        ADAPTIVE_FAKE_TTL_DEFAULT_FALLBACK
    };
    runtime_config.fake_packets.fake_ttl = fallback.clamp(min_ttl, max_ttl);
    runtime_config.fake_packets.adaptive_fake_ttl_enabled = false;
}

#[cfg(test)]
mod tests {
    use super::*;

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

    #[test]
    fn probe_runtime_transport_binds_ephemeral_port() {
        let spec = crate::candidates::candidate_spec("test", "Test", "test", test_ui_config());
        let runtime = probe_runtime_transport(&spec, None).expect("probe runtime should start with ephemeral port");
        assert_ne!(runtime.addr.port(), 0, "OS should assign a non-zero ephemeral port");
    }

    #[test]
    fn probe_runtime_transport_overrides_listen_ip_to_localhost() {
        let mut config = test_ui_config();
        config.listen.ip = "0.0.0.0".to_string();
        let spec = crate::candidates::candidate_spec("test", "Test", "test", config);
        let runtime = probe_runtime_transport(&spec, None).expect("probe runtime should start");
        assert!(runtime.addr.ip().is_loopback(), "probe runtime must bind to localhost");
    }
}
