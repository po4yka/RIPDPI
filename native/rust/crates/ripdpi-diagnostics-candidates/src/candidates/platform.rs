use socket2::{Domain, Protocol, Socket, Type};

pub fn probe_ip_fragmentation_capabilities() -> ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities {
    // Only the root-helper path returns an error; the unprivileged probe reports missing raw sockets
    // as false capabilities. Log the failure so it is not mistaken for an unsupported platform.
    ripdpi_runtime_platform::raw_packet::probe_ip_fragmentation_capabilities(None).unwrap_or_else(|error| {
        tracing::warn!(%error, "ip fragmentation capability probe failed; raw-socket candidates are skipped");
        ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities::default()
    })
}

pub fn supports_tcp_ip_fragmentation_for(
    capabilities: ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities,
) -> bool {
    capabilities.supports_tcp_ip_fragmentation(true)
}

pub fn supports_udp_ip_fragmentation_for(
    capabilities: ripdpi_runtime_platform::raw_packet::IpFragmentationCapabilities,
) -> bool {
    capabilities.supports_udp_ip_fragmentation(true)
}

pub fn supports_tcp_ip_fragmentation() -> bool {
    supports_tcp_ip_fragmentation_for(probe_ip_fragmentation_capabilities())
}

pub fn supports_udp_ip_fragmentation() -> bool {
    supports_udp_ip_fragmentation_for(probe_ip_fragmentation_capabilities())
}

pub fn probe_tcp_fast_open_capability() -> bool {
    let Ok(socket) = Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP)) else { return false };
    ripdpi_runtime_platform::socket::enable_tcp_fastopen_connect(&socket).is_ok()
}
