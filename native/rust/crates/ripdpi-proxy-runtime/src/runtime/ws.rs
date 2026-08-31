use std::io;
use std::net::{SocketAddr, TcpStream};

use ripdpi_proxy_runtime_adapter::model::config::{WsTunnelSettings, ws_tunnel_config_with};
use ripdpi_proxy_runtime_adapter::model::proxy_config::ProxyRuntimeContext;
use ripdpi_proxy_runtime_adapter::ws_bootstrap::{
    EncryptedDnsIpAnswers, MtprotoSeedClassification, TelegramDc, WsTransport, WsTunnelConfig, detect_telegram_dc,
    encrypted_dns_ip_answers_for_host, resolve_host_via_encrypted_dns, resolve_ws_tunnel_addr,
    should_tunnel_fallback_with, should_tunnel_first_with, telegram_dc_host,
};

pub(super) type RuntimeEncryptedDnsIpAnswers = EncryptedDnsIpAnswers;

pub(super) fn runtime_should_ws_tunnel_first(
    target: SocketAddr,
    settings: &WsTunnelSettings,
) -> Option<RuntimeTelegramDc> {
    should_tunnel_first_with(target, settings).map(RuntimeTelegramDc::from_adapter)
}

pub(super) fn runtime_should_ws_tunnel_fallback(
    target: SocketAddr,
    settings: &WsTunnelSettings,
) -> Option<RuntimeTelegramDc> {
    should_tunnel_fallback_with(target, settings).map(RuntimeTelegramDc::from_adapter)
}

pub(super) fn runtime_ws_tunnel_config(
    settings: &WsTunnelSettings,
    resolved_addr: Option<SocketAddr>,
) -> RuntimeWsTunnelConfig {
    RuntimeWsTunnelConfig::from_adapter(ws_tunnel_config_with(settings, resolved_addr))
}

pub(super) fn runtime_resolve_host_via_encrypted_dns(
    host: &str,
    runtime_context: Option<&ProxyRuntimeContext>,
    protect_path: Option<&str>,
    ipv6_enabled: bool,
) -> io::Result<SocketAddr> {
    resolve_host_via_encrypted_dns(host, runtime_context, protect_path, ipv6_enabled)
}

pub(super) fn runtime_encrypted_dns_ip_answers_for_host(
    host: &str,
    runtime_context: Option<&ProxyRuntimeContext>,
    protect_path: Option<&str>,
) -> io::Result<RuntimeEncryptedDnsIpAnswers> {
    encrypted_dns_ip_answers_for_host(host, runtime_context, protect_path)
}

pub(super) fn runtime_detect_telegram_dc(target: SocketAddr) -> Option<u8> {
    detect_telegram_dc(target)
}

pub(super) fn runtime_telegram_dc_host(dc: u8) -> String {
    telegram_dc_host(dc)
}

pub(super) fn runtime_resolve_ws_tunnel_addr(
    dc: RuntimeTelegramDc,
    runtime_context: Option<&ProxyRuntimeContext>,
    protect_path: Option<&str>,
) -> io::Result<SocketAddr> {
    resolve_ws_tunnel_addr(dc.into_adapter(), runtime_context, protect_path)
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) struct RuntimeTelegramDc(TelegramDc);

impl RuntimeTelegramDc {
    pub(super) fn number(self) -> u8 {
        self.0.number()
    }

    pub(super) fn raw(self) -> i32 {
        self.0.raw()
    }

    pub(super) fn class(self) -> impl std::fmt::Debug {
        self.0.class()
    }

    pub(super) fn into_adapter(self) -> TelegramDc {
        self.0
    }

    fn from_adapter(dc: TelegramDc) -> Self {
        Self(dc)
    }

    #[cfg(test)]
    pub(super) fn production(dc: u8) -> Self {
        Self(TelegramDc::production(dc))
    }

    #[cfg(test)]
    pub(super) fn from_raw(raw_dc: i32) -> Option<Self> {
        TelegramDc::from_raw(raw_dc).map(Self)
    }
}

pub(super) struct RuntimeWsTunnelConfig {
    inner: WsTunnelConfig,
    #[cfg(test)]
    pub(super) resolved_addr: Option<SocketAddr>,
    #[cfg(test)]
    pub(super) connect_timeout: Option<std::time::Duration>,
}

impl RuntimeWsTunnelConfig {
    fn from_adapter(inner: WsTunnelConfig) -> Self {
        Self {
            #[cfg(test)]
            resolved_addr: inner.resolved_addr,
            #[cfg(test)]
            connect_timeout: inner.connect_timeout,
            inner,
        }
    }

    fn as_adapter(&self) -> &WsTunnelConfig {
        &self.inner
    }
}

pub(super) enum WsSeedClassification {
    NotMtproto,
    UnmappableDc { raw_dc: i32, dc: Option<RuntimeTelegramDc> },
    ValidatedMtproto { dc: RuntimeTelegramDc },
}

pub(super) fn runtime_classify_mtproto_seed(transport: &dyn WsTransport, seed: &[u8]) -> WsSeedClassification {
    match transport.classify_mtproto_seed(seed) {
        MtprotoSeedClassification::NotMtproto => WsSeedClassification::NotMtproto,
        MtprotoSeedClassification::UnmappableDc { raw_dc, dc } => {
            WsSeedClassification::UnmappableDc { raw_dc, dc: dc.map(RuntimeTelegramDc::from_adapter) }
        }
        MtprotoSeedClassification::ValidatedMtproto { dc } => {
            WsSeedClassification::ValidatedMtproto { dc: RuntimeTelegramDc::from_adapter(dc) }
        }
    }
}

pub(super) fn runtime_relay_ws_tunnel(
    transport: &dyn WsTransport,
    client: TcpStream,
    dc: RuntimeTelegramDc,
    seed_request: Vec<u8>,
    config: &RuntimeWsTunnelConfig,
) -> io::Result<()> {
    transport.relay(client, dc.into_adapter(), seed_request, config.as_adapter())
}

#[cfg(test)]
mod tests {
    use super::*;
    use local_network_fixture::{FixtureConfig, FixtureStack};
    use ripdpi_proxy_runtime_adapter::model::config::{RuntimeConfig, WsTunnelMode, ws_tunnel_settings};
    use ripdpi_proxy_runtime_adapter::model::proxy_config::ProxyEncryptedDnsContext;
    use std::net::{IpAddr, Ipv4Addr, SocketAddr};
    use std::time::Duration;

    #[test]
    fn ws_tunnel_mode_wrappers_select_known_telegram_targets() {
        let target = SocketAddr::from((Ipv4Addr::new(149, 154, 167, 91), 443));
        let ipv6_target = SocketAddr::new("2001:67c:4e8::1".parse::<IpAddr>().expect("parse Telegram v6"), 443);
        let unrelated = SocketAddr::from((Ipv4Addr::new(203, 0, 113, 10), 443));

        let mut config = RuntimeConfig::default();
        config.adaptive.ws_tunnel_mode = WsTunnelMode::Always;
        let always = ws_tunnel_settings(&config);
        assert_eq!(runtime_should_ws_tunnel_first(target, &always), Some(RuntimeTelegramDc::production(2)));
        assert_eq!(runtime_should_ws_tunnel_first(ipv6_target, &always), Some(RuntimeTelegramDc::production(2)));
        assert_eq!(runtime_should_ws_tunnel_first(unrelated, &always), None);
        assert_eq!(runtime_should_ws_tunnel_fallback(target, &always), None);

        config.adaptive.ws_tunnel_mode = WsTunnelMode::Fallback;
        let fallback = ws_tunnel_settings(&config);
        assert_eq!(runtime_should_ws_tunnel_fallback(target, &fallback), Some(RuntimeTelegramDc::production(2)));
        assert_eq!(runtime_should_ws_tunnel_fallback(ipv6_target, &fallback), Some(RuntimeTelegramDc::production(2)));
        assert_eq!(runtime_should_ws_tunnel_fallback(unrelated, &fallback), None);
        assert_eq!(runtime_should_ws_tunnel_first(target, &fallback), None);
    }

    #[test]
    fn ws_tunnel_config_and_dc_helpers_project_adapter_values() {
        let resolved = SocketAddr::from((Ipv4Addr::LOCALHOST, 443));
        let mut config = RuntimeConfig::default();
        config.adaptive.ws_tunnel_fake_sni = Some("kws2.web.telegram.org".to_string());
        config.timeouts.connect_timeout_ms = 1_250;
        let settings = ws_tunnel_settings(&config);

        let ws_config = runtime_ws_tunnel_config(&settings, Some(resolved));
        assert_eq!(ws_config.resolved_addr, Some(resolved));
        assert_eq!(ws_config.connect_timeout, Some(Duration::from_millis(1_250)));
        assert_eq!(runtime_detect_telegram_dc(SocketAddr::from((Ipv4Addr::new(149, 154, 167, 91), 443))), Some(2));
        assert_eq!(
            runtime_detect_telegram_dc(SocketAddr::new(
                "2001:b28:f23f::1".parse::<IpAddr>().expect("parse Telegram v6"),
                443,
            )),
            Some(3),
        );
        assert_eq!(runtime_telegram_dc_host(4), "telegram-dc4");

        let dc = RuntimeTelegramDc::from_raw(10_002).expect("test dc");
        assert_eq!(dc.raw(), 10_002);
        assert_eq!(dc.number(), 2);
        assert!(format!("{:?}", dc.class()).contains("Test"));
        assert_eq!(RuntimeTelegramDc::production(2).raw(), 2);
    }

    #[test]
    fn encrypted_dns_ws_helpers_use_runtime_context() {
        let fixture = FixtureStack::start(dynamic_fixture_config()).expect("start fixture");
        let runtime_context = fixture_runtime_context(fixture.manifest().dns_http_port);

        let host_addr =
            runtime_resolve_host_via_encrypted_dns("kws2.web.telegram.org", Some(&runtime_context), None, true)
                .expect("fallback host addr");
        let answers = runtime_encrypted_dns_ip_answers_for_host("kws2.web.telegram.org", Some(&runtime_context), None)
            .expect("fallback dns answers");
        let tunnel_addr =
            runtime_resolve_ws_tunnel_addr(RuntimeTelegramDc::production(2), Some(&runtime_context), None)
                .expect("fallback bootstrap addr");

        assert_eq!(host_addr.port(), 0);
        assert!(!answers.answers.is_empty());
        assert_eq!(tunnel_addr.port(), 443);
    }

    fn fixture_runtime_context(dns_http_port: u16) -> ProxyRuntimeContext {
        ProxyRuntimeContext {
            encrypted_dns: Some(ProxyEncryptedDnsContext {
                resolver_id: Some("fixture-doh".to_string()),
                protocol: "doh".to_string(),
                host: "127.0.0.1".to_string(),
                port: dns_http_port,
                tls_server_name: None,
                bootstrap_ips: vec!["127.0.0.1".to_string()],
                doh_url: Some(format!("http://127.0.0.1:{dns_http_port}/dns-query")),
                dnscrypt_provider_name: None,
                dnscrypt_public_key: None,
            }),
            protect_path: None,
            preferred_edges: std::collections::BTreeMap::default(),
            direct_path_capabilities: Vec::new(),
            morph_policy: None,
            connection_concurrency: None,
        }
    }

    fn dynamic_fixture_config() -> FixtureConfig {
        FixtureConfig {
            tcp_echo_port: 0,
            udp_echo_port: 0,
            tls_echo_port: 0,
            dns_udp_port: 0,
            dns_http_port: 0,
            dns_dot_port: 0,
            dns_dnscrypt_port: 0,
            dns_doq_port: 0,
            dns_odoh_proxy_port: 0,
            dns_odoh_target_port: 0,
            socks5_port: 0,
            control_port: 0,
            ..FixtureConfig::default()
        }
    }
}
