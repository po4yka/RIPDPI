#![forbid(unsafe_code)]

mod client;
mod config;
mod error;
mod migration;
mod port_hopping;
pub mod quic_transport;
mod salamander;
mod tcp;
mod tls_quic;
mod udp;
mod varint;

pub use client::{HysteriaClient, connect};
pub use config::Config;
pub use error::{HysteriaError, Result};
pub use port_hopping::{HopIntervalTelemetry, PortHoppingWindow};
pub use quic_transport::{
    H3ClientParts, H3ConnectKind, H3Transport, QuicBiStream, QuicDatagramTransport, QuicTransport, QuicTransportConfig,
    build_client_udp_socket, build_client_udp_socket_with_policy, build_connect_request, build_quic_endpoint,
    maybe_rebind_endpoint,
};
pub use tcp::DuplexStream;
pub use udp::UdpSession;
