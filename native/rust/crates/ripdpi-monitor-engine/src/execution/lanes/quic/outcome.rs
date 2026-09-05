use std::net::SocketAddr;

use ripdpi_packets::{QuicResponseKind, validate_quic_response};

use crate::transport::{TransportError, UdpRelayResult};

pub(super) struct QuicProbeOutcome {
    pub(super) kind: String,
    pub(super) status: String,
    pub(super) error: String,
    pub(super) connected_addr: Option<SocketAddr>,
}

pub(super) fn classify_quic_response(
    response: Result<UdpRelayResult, TransportError>,
    request: &[u8],
) -> QuicProbeOutcome {
    let kind = response.as_ref().ok().and_then(|result| validate_quic_response(request, &result.payload));
    match response {
        Ok(result) if kind == Some(QuicResponseKind::Initial) => ok("quic_initial_response", result.connected_addr),
        Ok(result) if kind.is_some() => ok("quic_response", result.connected_addr),
        Ok(result) if result.payload.is_empty() => ok("quic_empty", result.connected_addr),
        Ok(result) => QuicProbeOutcome {
            kind: "quic_error".to_string(),
            status: "quic_error".to_string(),
            error: "invalid QUIC response".to_string(),
            connected_addr: result.connected_addr,
        },
        Err(error) => QuicProbeOutcome {
            kind: "quic_error".to_string(),
            status: "quic_error".to_string(),
            error: error.to_string(),
            connected_addr: None,
        },
    }
}

fn ok(status: &str, connected_addr: Option<SocketAddr>) -> QuicProbeOutcome {
    QuicProbeOutcome { kind: status.to_string(), status: status.to_string(), error: "none".to_string(), connected_addr }
}
