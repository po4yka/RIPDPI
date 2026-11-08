use std::net::SocketAddr;
use std::sync::Arc;

use super::super::association_state::UdpDestination;

pub(in crate::io_loop) enum UdpEvent {
    Packet {
        src: SocketAddr,
        association_id: u64,
        destination: Arc<UdpDestination>,
        raw: Vec<u8>,
    },
    RemoteError {
        src: SocketAddr,
        association_id: u64,
        registration_id: ripdpi_flow_app_attribution::FlowRegistrationId,
        destination: Arc<UdpDestination>,
        raw: Vec<u8>,
    },
    SetupFailed {
        src: SocketAddr,
        association_id: u64,
    },
    Closed {
        src: SocketAddr,
        association_id: u64,
    },
}
