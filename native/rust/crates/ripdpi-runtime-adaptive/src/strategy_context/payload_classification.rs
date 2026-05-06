use ripdpi_packets::classify::{default_registry, ProtocolId};
use ripdpi_runtime_policy::runtime_policy::TransportProtocol;

use crate::retry_stealth::RetryLane;

pub fn retry_lane_for_payload(transport: TransportProtocol, payload: Option<&[u8]>) -> RetryLane {
    let proto = payload.and_then(|bytes| default_registry().classify_id(bytes));
    match (transport, proto) {
        (TransportProtocol::Tcp, Some(ProtocolId::Tls)) => RetryLane::TcpTls,
        (TransportProtocol::Tcp, _) => RetryLane::TcpOther,
        (TransportProtocol::Udp, Some(ProtocolId::Quic)) => RetryLane::UdpQuic,
        (TransportProtocol::Udp, _) => RetryLane::UdpOther,
    }
}
