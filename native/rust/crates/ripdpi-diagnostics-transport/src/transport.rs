mod address;
mod config;
pub(crate) mod protect;
mod route_experiment;
mod socks5;
mod tcp;
mod types;
mod udp;

pub(crate) use address::resolve_addresses_with_timeout;
pub use address::{
    DnsResolveError, domain_connect_target, domain_connect_targets, quic_connect_target, quic_connect_targets,
    resolve_addresses, resolve_first_socket_addr, throughput_connect_targets,
};
pub use config::{describe_transport, direct_transport, transport_for_request_with_session};
pub use socks5::{
    decode_socks5_udp_frame, encode_socks5_udp_frame, negotiate_socks5, normalize_udp_relay_addr, relay_udp_via_socks5,
    socks5_noauth_handshake, socks5_udp_associate,
};
pub use tcp::{connect_direct, connect_transport_observed, wait_for_listener};
pub use types::{
    ConnectionStream, RouteExperimentConfig, RouteExperimentReport, Socks5Credentials, TargetAddress, TransportConfig,
    TransportConnectError, TransportConnectResult, TransportError, TransportFailureStage, UdpRelayResult,
};
pub use udp::{relay_udp_direct, relay_udp_payload_observed};
