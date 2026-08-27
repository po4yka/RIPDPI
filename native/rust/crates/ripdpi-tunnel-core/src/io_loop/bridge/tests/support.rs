use std::net::Ipv4Addr;

use smoltcp::iface::{Interface, SocketSet};
use smoltcp::socket::tcp::{self, Socket as TcpSocket};
use smoltcp::time::Instant;
use smoltcp::wire::IpAddress;

use crate::TunDevice;
use crate::io_loop::packet::{checksum_sum, finalize_checksum};
use crate::io_loop::tcp_accept::ensure_pending_listen_for_syn;

pub(super) use crate::io_loop::packet::build_ipv4_tcp_syn_packet;

pub(super) fn build_ipv4_tcp_ack_packet(
    src_ip: Ipv4Addr,
    dst_ip: Ipv4Addr,
    src_port: u16,
    dst_port: u16,
    seq: u32,
    ack: u32,
) -> Vec<u8> {
    let mut pkt = vec![0u8; 40];
    pkt[0] = 0x45;
    pkt[3] = 40;
    pkt[9] = 6;
    pkt[12..16].copy_from_slice(&src_ip.octets());
    pkt[16..20].copy_from_slice(&dst_ip.octets());
    pkt[20..22].copy_from_slice(&src_port.to_be_bytes());
    pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
    pkt[24..28].copy_from_slice(&seq.to_be_bytes());
    pkt[28..32].copy_from_slice(&ack.to_be_bytes());
    pkt[32] = 0x50;
    pkt[33] = 0x10; // ACK
    let ip_checksum = finalize_checksum(checksum_sum(&pkt[..20]));
    pkt[10..12].copy_from_slice(&ip_checksum.to_be_bytes());
    let mut sum = checksum_sum(&src_ip.octets());
    sum += checksum_sum(&dst_ip.octets());
    sum += u32::from(6u16);
    sum += u32::from((pkt.len() - 20) as u16);
    sum += checksum_sum(&pkt[20..]);
    let tcp_checksum = finalize_checksum(sum);
    pkt[36..38].copy_from_slice(&tcp_checksum.to_be_bytes());
    pkt
}

pub(super) fn build_ipv4_tcp_psh_packet(
    src_ip: Ipv4Addr,
    dst_ip: Ipv4Addr,
    src_port: u16,
    dst_port: u16,
    seq: u32,
    ack: u32,
    payload: &[u8],
) -> Vec<u8> {
    let total_len = 40 + payload.len();
    let mut pkt = vec![0u8; total_len];
    pkt[0] = 0x45;
    pkt[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
    pkt[9] = 6;
    pkt[12..16].copy_from_slice(&src_ip.octets());
    pkt[16..20].copy_from_slice(&dst_ip.octets());
    pkt[20..22].copy_from_slice(&src_port.to_be_bytes());
    pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
    pkt[24..28].copy_from_slice(&seq.to_be_bytes());
    pkt[28..32].copy_from_slice(&ack.to_be_bytes());
    pkt[32] = 0x50;
    pkt[33] = 0x18; // PSH+ACK
    pkt[40..].copy_from_slice(payload);
    let ip_checksum = finalize_checksum(checksum_sum(&pkt[..20]));
    pkt[10..12].copy_from_slice(&ip_checksum.to_be_bytes());
    let mut sum = checksum_sum(&src_ip.octets());
    sum += checksum_sum(&dst_ip.octets());
    sum += u32::from(6u16);
    sum += u32::from((pkt.len() - 20) as u16);
    sum += checksum_sum(&pkt[20..]);
    let tcp_checksum = finalize_checksum(sum);
    pkt[36..38].copy_from_slice(&tcp_checksum.to_be_bytes());
    pkt
}

pub(super) fn tcp_seq_ack(pkt: &[u8]) -> (u32, u32) {
    let ihl = ((pkt[0] & 0x0f) as usize) * 4;
    let seq = u32::from_be_bytes([pkt[ihl + 4], pkt[ihl + 5], pkt[ihl + 6], pkt[ihl + 7]]);
    let ack = u32::from_be_bytes([pkt[ihl + 8], pkt[ihl + 9], pkt[ihl + 10], pkt[ihl + 11]]);
    (seq, ack)
}

/// Set up a smoltcp Interface + SocketSet with a TCP socket in ESTABLISHED state.
/// Returns (socket_set, handle, server_seq) and the TunDevice for further interaction.
pub(super) fn establish_tcp_connection(
    device: &mut TunDevice,
) -> (Interface, SocketSet<'static>, smoltcp::iface::SocketHandle, u32) {
    let config = smoltcp::iface::Config::new(smoltcp::wire::HardwareAddress::Ip);
    let mut iface = Interface::new(config, device, Instant::now());
    iface.update_ip_addrs(|addrs| {
        addrs.push(smoltcp::wire::IpCidr::new(IpAddress::v4(10, 0, 0, 2), 24)).unwrap();
    });
    iface.routes_mut().add_default_ipv4_route(smoltcp::wire::Ipv4Address::new(10, 0, 0, 2)).expect("default route");
    iface.set_any_ip(true);

    let mut socket_set = SocketSet::new(vec![]);
    let mut pending_listens = std::collections::HashMap::new();

    let client_ip = Ipv4Addr::new(10, 0, 0, 99);
    let target_ip = Ipv4Addr::new(127, 0, 0, 1);

    // SYN -> creates pending listen -> smoltcp accepts
    let syn = build_ipv4_tcp_syn_packet(client_ip, target_ip, 51000, 443);
    ensure_pending_listen_for_syn(&syn, &mut pending_listens, &mut socket_set);
    device.rx_queue.push_back(syn);
    iface.poll(Instant::now(), device, &mut socket_set);

    // Get SYN-ACK
    let syn_ack = device.tx_queue.pop_front().expect("syn-ack from smoltcp");
    let (server_seq, _) = tcp_seq_ack(&syn_ack);

    // ACK to complete handshake
    let ack = build_ipv4_tcp_ack_packet(client_ip, target_ip, 51000, 443, 1, server_seq + 1);
    device.rx_queue.push_back(ack);
    iface.poll(Instant::now(), device, &mut socket_set);
    // Drain any ACK produced by smoltcp
    device.tx_queue.clear();

    // Get the socket handle from pending_listens
    let handle = pending_listens.values().next().map(|listener| listener.handle).expect("pending listen handle");
    let tcp = socket_set.get::<TcpSocket>(handle);
    assert_eq!(tcp.state(), tcp::State::Established, "TCP socket should be in ESTABLISHED state");

    (iface, socket_set, handle, server_seq)
}
