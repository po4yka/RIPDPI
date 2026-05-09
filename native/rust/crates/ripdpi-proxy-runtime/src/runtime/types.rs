use std::io;
use std::net::{SocketAddr, TcpStream};

use ripdpi_proxy_runtime_adapter::model::config::{
    ws_tunnel_config_with, DesyncGroup, FirstResponseSettings, IpIdMode, RelayGroupSettings, RotationPolicy,
    RuntimeTimeoutSettings, UdpGroupPacketSettings, UdpSourceRebindPolicy, WsTunnelSettings,
};
use ripdpi_proxy_runtime_adapter::model::decision::{
    ConnectionRoute, RetrySelectionPenalty, RouteAdvance, TransportProtocol,
};
use ripdpi_proxy_runtime_adapter::model::proxy_config::ProxyRuntimeContext;
use ripdpi_proxy_runtime_adapter::model::session::{
    classify_first_outbound_payload, classify_udp_payload_with, parse_socks5_udp_packet_with, ClientRequest,
    FirstOutboundPayloadPolicy, OutboundPayloadInfo, OutboundProgress, SessionError, SessionState, SocketType,
    UdpPacketParser, UdpPayloadClassifier, UdpPayloadInfo,
};
use ripdpi_proxy_runtime_adapter::protocol_payload::{
    build_probe_client_hello, FirstResponseBoundaryTracker, OutboundTlsClientHelloAssembler,
};
#[cfg(test)]
use ripdpi_proxy_runtime_adapter::protocol_payload::{
    TlsRecordBoundaryTracker, DEFAULT_FAKE_TLS, FIRST_TLS_CLIENT_HELLO_ASSEMBLY_TIMEOUT,
    FIRST_TLS_CLIENT_HELLO_BYTES_LIMIT,
};
use ripdpi_proxy_runtime_adapter::ws_bootstrap::{
    classify_mtproto_seed, detect_telegram_dc, encrypted_dns_ip_answers_for_host, relay_ws_tunnel,
    resolve_host_via_encrypted_dns, resolve_ws_tunnel_addr, should_tunnel_fallback_with, should_tunnel_first_with,
    telegram_dc_host, EncryptedDnsIpAnswers, MtprotoSeedClassification, TelegramDc, WsTunnelConfig,
};

pub(super) type RuntimeConnectionRoute = ConnectionRoute;
pub(super) type RuntimeRetrySelectionPenalty = RetrySelectionPenalty;
pub(super) type RuntimeRouteAdvance<'a> = RouteAdvance<'a>;
pub(super) type RuntimeTransportProtocol = TransportProtocol;
pub(super) type RuntimeFirstResponseBoundaryTracker = FirstResponseBoundaryTracker;
pub(super) type RuntimeOutboundTlsClientHelloAssembler = OutboundTlsClientHelloAssembler;
pub(super) type RuntimeEncryptedDnsIpAnswers = EncryptedDnsIpAnswers;
#[cfg(test)]
pub(super) type RuntimeTlsRecordBoundaryTracker = TlsRecordBoundaryTracker;
#[cfg(test)]
pub(super) const RUNTIME_DEFAULT_FAKE_TLS: &[u8] = DEFAULT_FAKE_TLS;
#[cfg(test)]
pub(super) const RUNTIME_FIRST_TLS_CLIENT_HELLO_ASSEMBLY_TIMEOUT: std::time::Duration =
    FIRST_TLS_CLIENT_HELLO_ASSEMBLY_TIMEOUT;
#[cfg(test)]
pub(super) const RUNTIME_FIRST_TLS_CLIENT_HELLO_BYTES_LIMIT: usize = FIRST_TLS_CLIENT_HELLO_BYTES_LIMIT;

pub(super) fn runtime_classify_first_outbound_payload(
    policy: &FirstOutboundPayloadPolicy,
    payload: &[u8],
) -> OutboundPayloadInfo {
    classify_first_outbound_payload(policy, payload)
}

pub(super) fn runtime_parse_socks5_udp_packet<'a>(
    parser: &UdpPacketParser,
    packet: &'a [u8],
    resolve_name: impl FnMut(&str, SocketType) -> Option<SocketAddr>,
) -> Option<(SocketAddr, &'a [u8])> {
    parse_socks5_udp_packet_with(parser, packet, resolve_name)
}

pub(super) fn runtime_classify_udp_payload(classifier: &UdpPayloadClassifier, payload: &[u8]) -> UdpPayloadInfo {
    classify_udp_payload_with(classifier, payload)
}

pub(super) fn runtime_first_response_boundary_tracker(
    request: &[u8],
    settings: FirstResponseSettings,
) -> RuntimeFirstResponseBoundaryTracker {
    FirstResponseBoundaryTracker::for_request(request, settings)
}

pub(super) fn runtime_outbound_tls_client_hello_assembler() -> RuntimeOutboundTlsClientHelloAssembler {
    OutboundTlsClientHelloAssembler::new()
}

pub(super) fn runtime_build_probe_client_hello(domain: &str) -> Vec<u8> {
    build_probe_client_hello(domain)
}

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
pub(super) enum RuntimeProxyProtocolMode {
    Transparent,
    HttpConnect,
    BytePrefixed { shadowsocks_enabled: bool },
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) struct RuntimeTarget {
    pub(super) addr: SocketAddr,
    pub(super) host: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(super) enum RuntimeClientRequest {
    Socks4Connect(RuntimeTarget),
    Socks5Connect(RuntimeTarget),
    Socks5UdpAssociate,
    HttpConnect(RuntimeTarget),
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub(super) struct RuntimeSessionError {
    pub(super) code: u8,
}

#[derive(Clone)]
pub(super) struct RuntimeSessionState(pub(super) SessionState);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) struct RuntimeOutboundProgress {
    pub(super) round: u32,
    pub(super) payload_size: usize,
    pub(super) stream_start: usize,
    pub(super) stream_end: usize,
}

#[derive(Clone, Copy)]
pub(super) struct UdpFlowGroupPolicy {
    pub(super) socket: RuntimeUdpSocketSettings,
    pub(super) packet: RuntimeUdpPacketSettings,
    pub(super) source_rebind: RuntimeUdpSourceRebindPolicy,
}

#[derive(Clone, Copy)]
pub(super) struct RuntimeUdpSocketSettings {
    pub(super) bind_low_port: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) struct RuntimeUdpPacketSettings {
    pub(super) default_ttl: u8,
    pub(super) ip_id_mode: Option<IpIdMode>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) struct RuntimeUdpSourceRebindPolicy {
    pub(super) after_handshake: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) struct RuntimeRelayTimeouts {
    pub(super) freeze_window_ms: u32,
    pub(super) freeze_min_bytes: u32,
    pub(super) freeze_max_stalls: u32,
}

pub(super) type RuntimeRelayRotationSeed = (DesyncGroup, RotationPolicy);

#[derive(Clone)]
pub(super) struct RuntimeRelayGroupSettings {
    inner: RelayGroupSettings,
}

impl RuntimeRelayGroupSettings {
    pub(super) fn from_adapter(inner: RelayGroupSettings) -> Self {
        Self { inner }
    }

    #[cfg(all(feature = "io-uring", any(target_os = "linux", target_os = "android")))]
    pub(super) fn rotation_enabled(&self) -> bool {
        self.inner.rotation_enabled
    }

    pub(super) fn drop_sack(&self) -> bool {
        self.inner.drop_sack
    }

    pub(super) fn timeouts(&self) -> RuntimeRelayTimeouts {
        runtime_relay_timeouts(self.inner.timeouts)
    }
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

    pub(super) fn from_adapter(dc: TelegramDc) -> Self {
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
    pub(super) fn from_adapter(inner: WsTunnelConfig) -> Self {
        Self {
            #[cfg(test)]
            resolved_addr: inner.resolved_addr,
            #[cfg(test)]
            connect_timeout: inner.connect_timeout,
            inner,
        }
    }

    pub(super) fn as_adapter(&self) -> &WsTunnelConfig {
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

pub(super) fn runtime_relay_timeouts(settings: RuntimeTimeoutSettings) -> RuntimeRelayTimeouts {
    RuntimeRelayTimeouts {
        freeze_window_ms: settings.freeze_window_ms,
        freeze_min_bytes: settings.freeze_min_bytes,
        freeze_max_stalls: settings.freeze_max_stalls,
    }
}

impl RuntimeRelayTimeouts {
    pub(super) fn into_adapter(self) -> RuntimeTimeoutSettings {
        RuntimeTimeoutSettings {
            freeze_window_ms: self.freeze_window_ms,
            freeze_min_bytes: self.freeze_min_bytes,
            freeze_max_stalls: self.freeze_max_stalls,
            ..RuntimeTimeoutSettings::default()
        }
    }
}

pub(super) fn runtime_client_request(request: ClientRequest) -> RuntimeClientRequest {
    match request {
        ClientRequest::Socks4Connect(target) => {
            RuntimeClientRequest::Socks4Connect(RuntimeTarget { addr: target.addr, host: target.host })
        }
        ClientRequest::Socks5Connect(target) => {
            RuntimeClientRequest::Socks5Connect(RuntimeTarget { addr: target.addr, host: target.host })
        }
        ClientRequest::Socks5UdpAssociate(_target) => RuntimeClientRequest::Socks5UdpAssociate,
        ClientRequest::HttpConnect(target) => {
            RuntimeClientRequest::HttpConnect(RuntimeTarget { addr: target.addr, host: target.host })
        }
    }
}

pub(super) fn runtime_session_error(error: SessionError) -> RuntimeSessionError {
    RuntimeSessionError { code: error.code }
}

pub(super) fn runtime_outbound_progress(progress: OutboundProgress) -> RuntimeOutboundProgress {
    RuntimeOutboundProgress {
        round: progress.round,
        payload_size: progress.payload_size,
        stream_start: progress.stream_start,
        stream_end: progress.stream_end,
    }
}

impl RuntimeOutboundProgress {
    pub(super) fn into_adapter(self) -> OutboundProgress {
        OutboundProgress {
            round: self.round,
            payload_size: self.payload_size,
            stream_start: self.stream_start,
            stream_end: self.stream_end,
        }
    }
}

pub(super) fn runtime_udp_packet_settings(settings: UdpGroupPacketSettings) -> RuntimeUdpPacketSettings {
    RuntimeUdpPacketSettings { default_ttl: settings.default_ttl, ip_id_mode: settings.ip_id_mode }
}

impl RuntimeUdpSourceRebindPolicy {
    #[cfg(test)]
    pub(super) fn after_handshake(after_handshake: bool) -> Self {
        Self { after_handshake }
    }

    pub(super) fn into_adapter(self) -> UdpSourceRebindPolicy {
        UdpSourceRebindPolicy { after_handshake: self.after_handshake }
    }
}
