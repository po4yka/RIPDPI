use std::net::{IpAddr, Ipv6Addr};

use anyhow::Context;
use smoltcp::iface::Interface;
use smoltcp::socket::{tcp, udp};
use smoltcp::wire::{IpAddress, IpCidr};

use crate::ports::VirtualPort;
use crate::support::MAX_PACKET;

pub(super) fn new_tcp_client_socket() -> tcp::Socket<'static> {
    let rx_data = vec![0u8; MAX_PACKET];
    let tx_data = vec![0u8; MAX_PACKET];
    let tcp_rx_buffer = tcp::SocketBuffer::new(rx_data);
    let tcp_tx_buffer = tcp::SocketBuffer::new(tx_data);
    tcp::Socket::new(tcp_rx_buffer, tcp_tx_buffer)
}

pub(super) fn new_udp_client_socket(
    source_peer_ip: IpAddr,
    virtual_port: VirtualPort,
) -> anyhow::Result<udp::Socket<'static>> {
    let rx_meta = vec![udp::PacketMetadata::EMPTY; 10];
    let tx_meta = vec![udp::PacketMetadata::EMPTY; 10];
    let rx_data = vec![0u8; MAX_PACKET];
    let tx_data = vec![0u8; MAX_PACKET];
    let udp_rx_buffer = udp::PacketBuffer::new(rx_meta, rx_data);
    let udp_tx_buffer = udp::PacketBuffer::new(tx_meta, tx_data);
    let mut socket = udp::Socket::new(udp_rx_buffer, udp_tx_buffer);
    socket.bind((IpAddress::from(source_peer_ip), virtual_port.num())).context("udp virtual client bind failed")?;
    Ok(socket)
}

/// Configure only local addresses; remote destinations use point-to-point default routes.
pub(super) fn configure_interface(
    iface: &mut Interface,
    source: IpAddr,
    source_v6: Option<Ipv6Addr>,
) -> anyhow::Result<()> {
    let IpAddr::V4(source_v4) = source else { anyhow::bail!("IPv4 source required") };
    let mut result = Ok(());
    iface.update_ip_addrs(|addrs| {
        result = addrs.push(IpCidr::new(source.into(), 32));
        if let Some(source_v6) = source_v6 {
            result = result.and_then(|()| addrs.push(IpCidr::new(source_v6.into(), 128)));
        }
    });
    result.map_err(|_| anyhow::anyhow!("virtual interface address capacity exceeded"))?;
    iface.routes_mut().add_default_ipv4_route(source_v4).map_err(|_| anyhow::anyhow!("IPv4 route table full"))?;
    if let Some(source_v6) = source_v6 {
        iface.routes_mut().add_default_ipv6_route(source_v6).map_err(|_| anyhow::anyhow!("IPv6 route table full"))?;
    }
    Ok(())
}

pub(super) fn select_source(source: IpAddr, source_v6: Option<Ipv6Addr>, destination: IpAddr) -> Option<IpAddr> {
    if destination.is_ipv4() { Some(source) } else { source_v6.map(IpAddr::V6) }
}
