use std::io;
use std::net::{SocketAddr, TcpStream};

use ripdpi_proxy_runtime_adapter::failure::{
    block_signal_from_failure, classify_first_response_closed_before_response,
    classify_first_response_partial_tls_timeout, classify_probe_connect_error, classify_probe_read_error,
    classify_probe_tls_response, classify_probe_write_error, classify_quic_probe, classify_relay_connection_freeze,
    classify_strategy_execution_failure, classify_transport_error, classify_warmup_closed_before_response,
    classify_warmup_first_response_error, classify_warmup_send_error, should_track_strategy_target, BlockSignal,
    BlockSignalObservation, ClassifiedFailure, FailureAction, FailureClass, FailureStage, ProbeResult,
};
use ripdpi_proxy_runtime_adapter::model::config::{
    DesyncGroup, IpIdMode, RelayGroupSettings, RotationPolicy, RuntimeTimeoutSettings, UdpGroupPacketSettings,
    UdpSourceRebindPolicy,
};
use ripdpi_proxy_runtime_adapter::model::decision::{
    classify_response_failure, response_requires_dns_tampering_evidence, ConnectionRoute, DnsTamperingEvidence,
    RetrySelectionPenalty, RouteAdvance, TransportProtocol,
};
use ripdpi_proxy_runtime_adapter::model::session::{
    classify_first_outbound_payload, classify_udp_payload_with, parse_socks5_udp_packet_with, ClientRequest,
    FirstOutboundPayloadPolicy, OutboundPayloadInfo, OutboundProgress, SessionError, SessionState, SocketType,
    UdpPacketParser, UdpPayloadClassifier, UdpPayloadInfo,
};
use ripdpi_proxy_runtime_adapter::ws_bootstrap::{
    classify_mtproto_seed, relay_ws_tunnel, MtprotoSeedClassification, TelegramDc, WsTunnelConfig,
};

pub(super) type RuntimeClassifiedFailure = ClassifiedFailure;
pub(super) type RuntimeBlockSignal = BlockSignal;
pub(super) type RuntimeBlockSignalObservation = BlockSignalObservation;
pub(super) type RuntimeFailureAction = FailureAction;
pub(super) type RuntimeFailureClass = FailureClass;
pub(super) type RuntimeFailureStage = FailureStage;
pub(super) type RuntimeConnectionRoute = ConnectionRoute;
pub(super) type RuntimeDnsTamperingEvidence<'a> = DnsTamperingEvidence<'a>;
pub(super) type RuntimeRetrySelectionPenalty = RetrySelectionPenalty;
pub(super) type RuntimeRouteAdvance<'a> = RouteAdvance<'a>;
pub(super) type RuntimeTransportProtocol = TransportProtocol;

pub(super) fn runtime_response_requires_dns_tampering_evidence(request: &[u8], response: &[u8]) -> bool {
    response_requires_dns_tampering_evidence(request, response)
}

pub(super) fn runtime_classify_response_failure(
    request: &[u8],
    response: &[u8],
    dns_evidence: Option<RuntimeDnsTamperingEvidence<'_>>,
) -> Option<RuntimeClassifiedFailure> {
    classify_response_failure(request, response, dns_evidence)
}

pub(super) fn runtime_classify_quic_probe(outcome: &str, error: Option<&str>) -> Option<RuntimeClassifiedFailure> {
    classify_quic_probe(outcome, error)
}

pub(super) fn runtime_classify_transport_error(
    stage: RuntimeFailureStage,
    source: &io::Error,
) -> RuntimeClassifiedFailure {
    classify_transport_error(stage, source)
}

pub(super) fn runtime_classify_strategy_execution_failure(
    stage: RuntimeFailureStage,
    action: &'static str,
    source_kind: io::ErrorKind,
    source_errno: Option<i32>,
    description: String,
) -> Option<RuntimeClassifiedFailure> {
    classify_strategy_execution_failure(stage, action, source_kind, source_errno, description)
}

pub(super) fn runtime_classify_first_response_closed_before_response() -> RuntimeClassifiedFailure {
    classify_first_response_closed_before_response()
}

pub(super) fn runtime_classify_first_response_partial_tls_timeout() -> RuntimeClassifiedFailure {
    classify_first_response_partial_tls_timeout()
}

pub(super) fn runtime_classify_relay_connection_freeze(timeouts: RuntimeTimeoutSettings) -> RuntimeClassifiedFailure {
    classify_relay_connection_freeze(timeouts)
}

pub(super) fn runtime_classify_warmup_send_error(source: &io::Error) -> RuntimeClassifiedFailure {
    classify_warmup_send_error(source)
}

pub(super) fn runtime_classify_warmup_first_response_error(source: &io::Error) -> RuntimeClassifiedFailure {
    classify_warmup_first_response_error(source)
}

pub(super) fn runtime_classify_warmup_closed_before_response() -> RuntimeClassifiedFailure {
    classify_warmup_closed_before_response()
}

pub(super) fn runtime_classify_probe_connect_error(source: &io::Error) -> RuntimeProbeResult {
    runtime_probe_result(classify_probe_connect_error(source))
}

pub(super) fn runtime_classify_probe_write_error(source: &io::Error) -> RuntimeProbeResult {
    runtime_probe_result(classify_probe_write_error(source))
}

pub(super) fn runtime_classify_probe_read_error(source: &io::Error) -> RuntimeProbeResult {
    runtime_probe_result(classify_probe_read_error(source))
}

pub(super) fn runtime_classify_probe_tls_response(header: [u8; 5], handshake_type: Option<u8>) -> RuntimeProbeResult {
    runtime_probe_result(classify_probe_tls_response(header, handshake_type))
}

pub(super) fn runtime_should_track_strategy_target(target: SocketAddr) -> bool {
    should_track_strategy_target(target)
}

pub(super) fn runtime_block_signal_from_failure(
    failure: &RuntimeClassifiedFailure,
    tcp_total_retransmissions: Option<u32>,
) -> Option<RuntimeBlockSignalObservation> {
    block_signal_from_failure(failure, tcp_total_retransmissions)
}

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

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(super) enum RuntimeProbeResult {
    Success,
    DpiFailure(&'static str),
    NetworkError(&'static str),
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

pub(super) fn runtime_probe_result(result: ProbeResult) -> RuntimeProbeResult {
    match result {
        ProbeResult::Success => RuntimeProbeResult::Success,
        ProbeResult::DpiFailure(reason) => RuntimeProbeResult::DpiFailure(reason),
        ProbeResult::NetworkError(reason) => RuntimeProbeResult::NetworkError(reason),
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
