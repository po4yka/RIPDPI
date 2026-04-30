use std::net::IpAddr;

use anyhow::Context;
use smoltcp::socket::{tcp, udp};
use smoltcp::wire::IpAddress;

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
