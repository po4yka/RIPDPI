use ripdpi_packets::classify::{default_registry, ProtocolId};
use ripdpi_packets::{is_quic_initial, parse_quic_initial, tls_marker_info};
use ripdpi_runtime_policy::runtime_policy::is_tls_client_hello_payload;
use ripdpi_runtime_policy::runtime_policy::TransportProtocol;

use crate::retry_stealth::RetryLane;

pub struct LearningPayloadClassification {
    pub is_tls: bool,
    pub has_ech: bool,
    pub is_quic: bool,
}

pub fn classify_learning_payload(transport: TransportProtocol, payload: &[u8]) -> LearningPayloadClassification {
    match transport {
        TransportProtocol::Tcp => {
            let is_tls = is_tls_client_hello_payload(payload);
            let has_ech = is_tls && tls_marker_info(payload).and_then(|markers| markers.ech_ext_start).is_some();
            LearningPayloadClassification { is_tls, has_ech, is_quic: false }
        }
        TransportProtocol::Udp => {
            let parsed_quic = parse_quic_initial(payload);
            let has_ech = parsed_quic.as_ref().and_then(|info| info.tls_info.ech_ext_start).is_some();
            LearningPayloadClassification { is_tls: false, has_ech, is_quic: is_quic_initial(payload) }
        }
    }
}

pub fn retry_lane_for_payload(transport: TransportProtocol, payload: Option<&[u8]>) -> RetryLane {
    let proto = payload.and_then(|bytes| default_registry().classify_id(bytes));
    match (transport, proto) {
        (TransportProtocol::Tcp, Some(ProtocolId::Tls)) => RetryLane::TcpTls,
        (TransportProtocol::Tcp, _) => RetryLane::TcpOther,
        (TransportProtocol::Udp, Some(ProtocolId::Quic)) => RetryLane::UdpQuic,
        (TransportProtocol::Udp, _) => RetryLane::UdpOther,
    }
}
