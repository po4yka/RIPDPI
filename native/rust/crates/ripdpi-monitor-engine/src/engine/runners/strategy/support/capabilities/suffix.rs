use ripdpi_runtime_platform::capability::RuntimeCapability;

pub(in crate::engine::runners::strategy) fn capability_suffix(capability: RuntimeCapability) -> &'static str {
    match capability {
        RuntimeCapability::TtlWrite => " — ttl_write unavailable",
        RuntimeCapability::RawTcpFakeSend => " — raw_tcp_fake_send unavailable",
        RuntimeCapability::RawUdpFragmentation => " — raw_udp_fragmentation unavailable",
        RuntimeCapability::ReplacementSocket => " — replacement_socket unavailable",
        RuntimeCapability::RootHelperAvailable => " — root_helper_available unavailable",
        RuntimeCapability::VpnProtect => " — vpn_protect unavailable",
        RuntimeCapability::VpnProtectCallback => " — vpn_protect_callback unavailable",
        RuntimeCapability::VpnMode => " — vpn_mode unavailable",
        RuntimeCapability::TcpWindowClamp => " — tcp_window_clamp unavailable",
        RuntimeCapability::NetworkBinding => " — network_binding unavailable",
    }
}
