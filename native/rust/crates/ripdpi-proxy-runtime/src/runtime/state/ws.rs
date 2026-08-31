use super::*;

impl RuntimeState {
    pub(in crate::runtime) fn should_ws_tunnel_first(&self, target: SocketAddr) -> Option<RuntimeTelegramDc> {
        runtime_should_ws_tunnel_first(target, &self.ws_tunnel_settings)
    }
    pub(in crate::runtime) fn should_ws_tunnel_fallback(&self, target: SocketAddr) -> Option<RuntimeTelegramDc> {
        runtime_should_ws_tunnel_fallback(target, &self.ws_tunnel_settings)
    }
    pub(in crate::runtime) fn ws_tunnel_config(&self, resolved_addr: Option<SocketAddr>) -> RuntimeWsTunnelConfig {
        runtime_ws_tunnel_config(&self.ws_tunnel_settings, resolved_addr)
    }
    pub(in crate::runtime) fn classify_mtproto_seed(&self, seed: &[u8]) -> WsSeedClassification {
        runtime_classify_mtproto_seed(self.ws_transport.as_ref(), seed)
    }
    pub(in crate::runtime) fn relay_ws_tunnel(
        &self,
        client: TcpStream,
        dc: RuntimeTelegramDc,
        seed_request: Vec<u8>,
        config: &RuntimeWsTunnelConfig,
    ) -> io::Result<()> {
        runtime_relay_ws_tunnel(self.ws_transport.as_ref(), client, dc, seed_request, config)
    }
    pub(in crate::runtime) fn note_telegram_dc_detected(&self, target: SocketAddr, dc: u8) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_telegram_dc_detected(target, dc);
        }
    }
    pub(in crate::runtime) fn detect_telegram_dc(target: SocketAddr) -> Option<u8> {
        runtime_detect_telegram_dc(target)
    }
    pub(in crate::runtime) fn telegram_dc_host(dc: u8) -> String {
        runtime_telegram_dc_host(dc)
    }
    pub(in crate::runtime) fn telegram_dc_host_hint(&self, target: SocketAddr) -> Option<String> {
        let dc = Self::detect_telegram_dc(target)?;
        self.note_telegram_dc_detected(target, dc);
        Some(Self::telegram_dc_host(dc))
    }
    pub(in crate::runtime) fn note_ws_tunnel_escalation(&self, target: SocketAddr, dc: u8, success: bool) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_ws_tunnel_escalation(target, dc, success);
        }
    }
    /// True when the configured ws-tunnel uses the fake-SNI cover with the
    /// operator opt-in set, i.e. this connection's TLS cert verification is
    /// disabled. Drives the `fake_sni_active` telemetry counter.
    pub(in crate::runtime) fn ws_tunnel_fake_sni_active(&self) -> bool {
        self.ws_tunnel_settings.allow_insecure_sni && self.ws_tunnel_settings.fake_sni.is_some()
    }
    pub(in crate::runtime) fn note_ws_tunnel_fake_sni_active(&self, target: SocketAddr, dc: u8) {
        if let Some(telemetry) = &self.telemetry {
            telemetry.on_ws_tunnel_fake_sni_active(target, dc);
        }
    }
    pub(in crate::runtime) fn resolve_ws_tunnel_addr(&self, dc: RuntimeTelegramDc) -> io::Result<SocketAddr> {
        if let Some(worker_route) = &self.ws_tunnel_settings.worker_route {
            return resolve_worker_route_addr_with(worker_route, |host| {
                runtime_resolve_host_via_encrypted_dns(
                    host,
                    self.runtime_context.as_ref(),
                    self.ws_tunnel_settings.protect_path.as_deref(),
                    false,
                )
            });
        }
        runtime_resolve_ws_tunnel_addr(
            dc,
            self.runtime_context.as_ref(),
            self.ws_tunnel_settings.protect_path.as_deref(),
        )
    }
}

fn resolve_worker_route_addr_with(
    worker_route: &ripdpi_proxy_runtime_adapter::ws_bootstrap::CloudflareWorkerRoute,
    resolve_host: impl FnOnce(&str) -> io::Result<SocketAddr>,
) -> io::Result<SocketAddr> {
    let resolved = resolve_host(worker_route.host())?;
    Ok(SocketAddr::new(resolved.ip(), worker_route.port()))
}

#[cfg(test)]
mod tests {
    use super::*;

    use std::net::{IpAddr, Ipv4Addr};

    #[test]
    fn worker_route_resolution_uses_worker_host_and_port() {
        let route = ripdpi_proxy_runtime_adapter::ws_bootstrap::CloudflareWorkerRoute::parse(
            "https://edge.example.workers.dev:8443/relay",
            "secret-token",
        )
        .expect("valid worker route");

        let resolved = resolve_worker_route_addr_with(&route, |host| {
            assert_eq!(host, "edge.example.workers.dev");
            Ok(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 10)), 443))
        })
        .expect("resolve worker route");

        assert_eq!(resolved, SocketAddr::new(IpAddr::V4(Ipv4Addr::new(203, 0, 113, 10)), 8443));
    }
}
