use std::io;
use std::net::{SocketAddr, TcpStream};

use ripdpi_proxy_runtime_adapter::model::config::{ws_tunnel_config_with, WsTunnelSettings};
use ripdpi_proxy_runtime_adapter::model::proxy_config::ProxyRuntimeContext;
use ripdpi_proxy_runtime_adapter::ws_bootstrap::{
    classify_mtproto_seed, detect_telegram_dc, encrypted_dns_ip_answers_for_host, relay_ws_tunnel,
    resolve_host_via_encrypted_dns, resolve_ws_tunnel_addr, should_tunnel_fallback_with, should_tunnel_first_with,
    telegram_dc_host, EncryptedDnsIpAnswers, MtprotoSeedClassification, TelegramDc, WsTunnelConfig,
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

    #[cfg(all(test, not(feature = "loom")))]
    pub(super) fn production(dc: u8) -> Self {
        Self(TelegramDc::production(dc))
    }

    #[cfg(all(test, not(feature = "loom")))]
    pub(super) fn from_raw(raw_dc: i32) -> Option<Self> {
        TelegramDc::from_raw(raw_dc).map(Self)
    }
}

pub(super) struct RuntimeWsTunnelConfig {
    inner: WsTunnelConfig,
    #[cfg(all(test, not(feature = "loom")))]
    pub(super) resolved_addr: Option<SocketAddr>,
    #[cfg(all(test, not(feature = "loom")))]
    pub(super) connect_timeout: Option<std::time::Duration>,
}

impl RuntimeWsTunnelConfig {
    fn from_adapter(inner: WsTunnelConfig) -> Self {
        Self {
            #[cfg(all(test, not(feature = "loom")))]
            resolved_addr: inner.resolved_addr,
            #[cfg(all(test, not(feature = "loom")))]
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

pub(super) fn runtime_classify_mtproto_seed(seed: &[u8]) -> WsSeedClassification {
    match classify_mtproto_seed(seed) {
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
    client: TcpStream,
    dc: RuntimeTelegramDc,
    seed_request: Vec<u8>,
    config: &RuntimeWsTunnelConfig,
) -> io::Result<()> {
    relay_ws_tunnel(client, dc.into_adapter(), seed_request, config.as_adapter())
}
