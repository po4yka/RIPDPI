use std::net::SocketAddr;

use ripdpi_packets::parse_quic_initial;

use crate::transport::UdpRelayResult;

pub(super) struct QuicProbeOutcome {
    pub(super) kind: String,
    pub(super) status: String,
    pub(super) error: String,
    pub(super) connected_addr: Option<SocketAddr>,
}

pub(super) fn classify_quic_response(response: Result<UdpRelayResult, String>) -> QuicProbeOutcome {
    match response {
        Ok(result) if parse_quic_initial(&result.payload).is_some() => {
            ok("quic_initial_response", result.connected_addr)
        }
        Ok(result) if !result.payload.is_empty() => ok("quic_response", result.connected_addr),
        Ok(result) => ok("quic_empty", result.connected_addr),
        Err(error) => QuicProbeOutcome {
            kind: "quic_error".to_string(),
            status: "quic_error".to_string(),
            error,
            connected_addr: None,
        },
    }
}

fn ok(status: &str, connected_addr: Option<SocketAddr>) -> QuicProbeOutcome {
    QuicProbeOutcome { kind: status.to_string(), status: status.to_string(), error: "none".to_string(), connected_addr }
}
