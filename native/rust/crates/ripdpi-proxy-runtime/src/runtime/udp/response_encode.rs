use std::net::SocketAddr;

use super::encode_socks5_udp_packet;

pub(super) fn encode_udp_response(sender: SocketAddr, payload: &[u8]) -> Vec<u8> {
    encode_socks5_udp_packet(sender, payload)
}
