pub(super) use ripdpi_proxy_runtime_adapter::model::protocol_auth::validate_http_proxy_auth;
pub(super) use ripdpi_proxy_runtime_adapter::model::session::{
    encode_http_connect_reply, encode_socks4_reply, encode_socks5_reply, encode_socks5_udp_packet,
    encode_upstream_socks_connect, extract_payload_host_with, has_inbound_payload, new_session_state,
    observe_datagram_outbound_payload, observe_first_response_payload, observe_inbound_payload,
    observe_outbound_payload, observe_retry_response_payload, outbound_payload_count_this_round,
    parse_http_connect_request, parse_shadowsocks_target, parse_socks4_request, parse_socks5_request,
    read_upstream_socks_reply, FirstOutboundPayloadPolicy, OutboundPayloadInfo, PayloadHostExtractor, ProxyReply,
    SocketType, UdpPacketParser, UdpPayloadClassifier, UdpPayloadInfo, S_ATP_I4, S_ATP_I6, S_AUTH_BAD, S_AUTH_NONE,
    S_AUTH_USERPASS, S_ER_CMD, S_ER_GEN, S_VER5,
};

use ripdpi_proxy_runtime_adapter::model::config::RuntimeConfig;
use ripdpi_proxy_runtime_adapter::model::session::{
    first_outbound_payload_policy, payload_host_extractor, udp_packet_parser, udp_payload_classifier,
};

pub(super) struct RuntimeSessionProjection {
    pub(super) udp_packet_parser: UdpPacketParser,
    pub(super) udp_payload_classifier: UdpPayloadClassifier,
    pub(super) relay_host_extractor: PayloadHostExtractor,
    pub(super) first_outbound_payload_policy: FirstOutboundPayloadPolicy,
}

pub(super) fn runtime_session_projection(config: &RuntimeConfig) -> RuntimeSessionProjection {
    RuntimeSessionProjection {
        udp_packet_parser: udp_packet_parser(config),
        udp_payload_classifier: udp_payload_classifier(config),
        relay_host_extractor: payload_host_extractor(config),
        first_outbound_payload_policy: first_outbound_payload_policy(config),
    }
}
