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
    pub(in crate::runtime) fn classify_mtproto_seed(seed: &[u8]) -> WsSeedClassification {
        runtime_classify_mtproto_seed(seed)
    }
    pub(in crate::runtime) fn relay_ws_tunnel(
        client: TcpStream,
        dc: RuntimeTelegramDc,
        seed_request: Vec<u8>,
        config: &RuntimeWsTunnelConfig,
    ) -> io::Result<()> {
        runtime_relay_ws_tunnel(client, dc, seed_request, config)
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
        runtime_resolve_ws_tunnel_addr(
            dc,
            self.runtime_context.as_ref(),
            self.ws_tunnel_settings.protect_path.as_deref(),
        )
    }
}
