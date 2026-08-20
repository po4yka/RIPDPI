use std::net::SocketAddr;

use ripdpi_proxy_runtime_adapter::model::config::{IpIdMode, UdpGroupPacketSettings, UdpSourceRebindPolicy};

#[derive(Clone, Copy)]
pub(in crate::runtime) struct UdpFlowGroupPolicy {
    pub(in crate::runtime) socket: RuntimeUdpSocketSettings,
    pub(in crate::runtime) packet: RuntimeUdpPacketSettings,
    pub(in crate::runtime) source_rebind: RuntimeUdpSourceRebindPolicy,
    pub(in crate::runtime) execution_family: Option<&'static str>,
    /// Single upstream SOCKS5 server this flow must reach via UDP ASSOCIATE,
    /// projected from `group.policy.ext_socks` exactly as the TCP CONNECT path
    /// projects it (`tcp_connect.rs`). `None` ⇒ direct egress to the target.
    pub(in crate::runtime) upstream_socks_addr: Option<SocketAddr>,
    /// Control-TCP connect timeout for the UDP ASSOCIATE handshake.
    pub(in crate::runtime) connect_timeout: Option<std::time::Duration>,
}

#[derive(Clone, Copy)]
pub(in crate::runtime) struct RuntimeUdpSocketSettings {
    pub(in crate::runtime) bind_low_port: bool,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(in crate::runtime) struct RuntimeUdpPacketSettings {
    pub(in crate::runtime) default_ttl: u8,
    pub(in crate::runtime) ip_id_mode: Option<IpIdMode>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub(in crate::runtime) struct RuntimeUdpSourceRebindPolicy {
    pub(in crate::runtime) after_handshake: bool,
}

pub(in crate::runtime) fn runtime_udp_packet_settings(settings: UdpGroupPacketSettings) -> RuntimeUdpPacketSettings {
    RuntimeUdpPacketSettings { default_ttl: settings.default_ttl, ip_id_mode: settings.ip_id_mode }
}

impl RuntimeUdpSourceRebindPolicy {
    #[cfg(test)]
    pub(in crate::runtime) fn after_handshake(after_handshake: bool) -> Self {
        Self { after_handshake }
    }

    pub(in crate::runtime) fn into_adapter(self) -> UdpSourceRebindPolicy {
        UdpSourceRebindPolicy { after_handshake: self.after_handshake }
    }
}
