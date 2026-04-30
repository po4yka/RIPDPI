use socket2::{Domain, Protocol, Socket, Type};

pub fn probe_ip_fragmentation_capabilities() -> ripdpi_runtime_platform::IpFragmentationCapabilities {
    ripdpi_runtime_platform::probe_ip_fragmentation_capabilities(None).unwrap_or_default()
}

pub fn supports_tcp_ip_fragmentation_for(capabilities: ripdpi_runtime_platform::IpFragmentationCapabilities) -> bool {
    capabilities.supports_tcp_ip_fragmentation(true)
}

pub fn supports_udp_ip_fragmentation_for(capabilities: ripdpi_runtime_platform::IpFragmentationCapabilities) -> bool {
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
    ripdpi_runtime_platform::enable_tcp_fastopen_connect(&socket).is_ok()
}
