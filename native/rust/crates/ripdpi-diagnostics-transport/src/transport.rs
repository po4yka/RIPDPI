mod abort;
mod address;
mod config;
pub(crate) mod protect;
mod route_experiment;
mod socks5;
mod tcp;
mod types;
mod udp;
mod watchdog;

#[cfg(test)]
pub(crate) use address::resolve_addresses_with_timeout;
pub use address::{
    DnsResolveError, domain_connect_target, domain_connect_targets, interface_index, quic_connect_target,
    quic_connect_targets, resolve_addresses, resolve_addresses_with_cancel, resolve_first_socket_addr,
    throughput_connect_targets,
};
pub use config::{describe_transport, direct_transport, transport_for_request_with_session};
pub use socks5::{
    decode_socks5_udp_frame, encode_socks5_udp_frame, negotiate_socks5, normalize_udp_relay_addr, relay_udp_via_socks5,
    socks5_noauth_handshake, socks5_udp_associate,
};
pub use tcp::{
    connect_direct, connect_direct_address, connect_direct_address_with_abort, connect_transport_observed,
    connect_transport_observed_with_abort, wait_for_listener,
};
pub use types::{
    ConnectionStream, RouteExperimentConfig, RouteExperimentReport, Socks5Credentials, TargetAddress, TransportConfig,
    TransportConnectError, TransportConnectResult, TransportError, TransportFailureStage, UdpRelayResult,
};
pub use udp::{
    relay_quic_probe_observed, relay_udp_direct, relay_udp_payload_matching_observed, relay_udp_payload_observed,
};
pub use watchdog::{run_with_socket_abort_watchdog, run_with_socket_watchdog};

pub use address::start_address_resolution;
pub use protect::{protected_tcp_socket, protected_udp_bind};
