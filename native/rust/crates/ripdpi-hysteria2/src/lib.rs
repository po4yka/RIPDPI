#![forbid(unsafe_code)]

mod client;
mod config;
mod config_uri;
mod error;
mod hopping_policy;
mod hopping_socket;
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
pub use hopping_policy::{MAX_HOP_INTERVAL_MILLIS, MIN_HOP_INTERVAL_MILLIS, PortHoppingConfig};
pub use port_hopping::{HopIntervalTelemetry, PortHoppingWindow};
pub use quic_transport::{
    CertificatePinSha256, H3ClientParts, H3ConnectKind, H3Transport, QuicBiStream, QuicDatagramTransport,
    QuicTransport, QuicTransportConfig, build_client_udp_socket, build_client_udp_socket_with_policy,
    build_connect_request, build_quic_endpoint, maybe_rebind_endpoint, parse_certificate_pin_sha256,
};
pub use tcp::DuplexStream;
pub use udp::UdpSession;
