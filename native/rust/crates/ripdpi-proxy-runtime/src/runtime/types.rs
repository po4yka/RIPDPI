use std::net::SocketAddr;

use ripdpi_proxy_runtime_adapter::model::config::{
    DesyncGroup, IpIdMode, RelayGroupSettings, RotationPolicy, RuntimeTimeoutSettings, UdpGroupPacketSettings,
    UdpSourceRebindPolicy,
};
use ripdpi_proxy_runtime_adapter::model::decision::{
    ConnectionRoute, RetrySelectionPenalty, RouteAdvance, TransportProtocol,
};
use ripdpi_proxy_runtime_adapter::model::session::{
    classify_first_outbound_payload, classify_udp_payload_with, parse_socks5_udp_packet_with, ClientRequest,
    FirstOutboundPayloadPolicy, OutboundPayloadInfo, OutboundProgress, SessionError, SessionState, SocketType,
    UdpPacketParser, UdpPayloadClassifier, UdpPayloadInfo,
};

pub(super) type RuntimeConnectionRoute = ConnectionRoute;
pub(super) type RuntimeRetrySelectionPenalty = RetrySelectionPenalty;
pub(super) type RuntimeRouteAdvance<'a> = RouteAdvance<'a>;
pub(super) type RuntimeTransportProtocol = TransportProtocol;
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
