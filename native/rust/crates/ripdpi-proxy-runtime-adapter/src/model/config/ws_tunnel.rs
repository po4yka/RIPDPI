use std::net::SocketAddr;
use std::time::Duration;

use ripdpi_config::{RuntimeConfig, WsTunnelMode};
use ripdpi_ws_bootstrap::CloudflareWorkerRoute;

use super::{connect_timeout, protect_path_owned};

pub fn ws_tunnel_always_enabled(config: &RuntimeConfig) -> bool {
    config.adaptive.ws_tunnel_mode == WsTunnelMode::Always
}

pub fn ws_tunnel_fallback_enabled(config: &RuntimeConfig) -> bool {
    config.adaptive.ws_tunnel_mode == WsTunnelMode::Fallback
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct WsTunnelSettings {
    pub always_enabled: bool,
    pub fallback_enabled: bool,
    pub protect_path: Option<String>,
    pub connect_timeout: Option<Duration>,
    pub fake_sni: Option<String>,
    pub allow_insecure_sni: bool,
    pub worker_route: Option<CloudflareWorkerRoute>,
}

pub fn ws_tunnel_settings(config: &RuntimeConfig) -> WsTunnelSettings {
    WsTunnelSettings {
        always_enabled: ws_tunnel_always_enabled(config),
        fallback_enabled: ws_tunnel_fallback_enabled(config),
        protect_path: protect_path_owned(config),
        connect_timeout: connect_timeout(config),
        fake_sni: config.adaptive.ws_tunnel_fake_sni.clone(),
        allow_insecure_sni: config.adaptive.ws_tunnel_allow_insecure_sni,
        worker_route: config.adaptive.ws_tunnel_worker_route.as_ref().map(|route| {
            // Infallible: RuntimeWsTunnelWorkerRoute has private fields and its constructor enforces
            // the same scheme, authority, port, fragment, userinfo, and bearer invariants.
            CloudflareWorkerRoute::parse(route.url(), route.bearer().expose_secret())
                .expect("RuntimeWsTunnelWorkerRoute must satisfy CloudflareWorkerRoute invariants")
        }),
    }
}

pub fn ws_tunnel_config(
    config: &RuntimeConfig,
    resolved_addr: Option<SocketAddr>,
) -> ripdpi_ws_bootstrap::WsTunnelConfig {
    ws_tunnel_config_with(&ws_tunnel_settings(config), resolved_addr)
}

pub fn ws_tunnel_config_with(
    settings: &WsTunnelSettings,
    resolved_addr: Option<SocketAddr>,
) -> ripdpi_ws_bootstrap::WsTunnelConfig {
    ripdpi_ws_bootstrap::WsTunnelConfig {
        protect_path: settings.protect_path.clone(),
        resolved_addr,
        connect_timeout: settings.connect_timeout,
        fake_sni: settings.fake_sni.clone(),
        // Operator opt-in plumbed from RuntimeConfig.adaptive. When false (the
        // default), ripdpi_ws_bootstrap refuses any fake_sni value with
        // PermissionDenied rather than silently bypassing TLS verification.
        allow_insecure_sni: settings.allow_insecure_sni,
        worker_route: settings.worker_route.clone(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    use ripdpi_config::RuntimeWsTunnelWorkerRoute;

    #[test]
    fn ws_tunnel_settings_maps_valid_worker_route() {
        let mut config = RuntimeConfig::default();
        config.adaptive.ws_tunnel_worker_route = Some(
            RuntimeWsTunnelWorkerRoute::parse(
                "https://edge.example.workers.dev:8443/relay".to_string(),
                "secret-token".to_string(),
            )
            .expect("valid runtime worker route"),
        );

        let settings = ws_tunnel_settings(&config);
        let route = settings.worker_route.expect("worker route");

        assert_eq!(route.host(), "edge.example.workers.dev");
        assert_eq!(route.port(), 8443);
        assert_eq!(route.request_path(), "/relay");
        assert_eq!(route.bearer().expose_secret(), "secret-token");
        assert!(!format!("{route:?}").contains("secret-token"));
    }

    #[test]
    fn runtime_worker_route_cannot_be_dropped_during_projection() {
        for url in ["https://edge.example:0/relay", "https://edge.example:invalid/relay"] {
            assert!(RuntimeWsTunnelWorkerRoute::parse(url.to_string(), "secret-token".to_string()).is_err());
        }
    }
}
