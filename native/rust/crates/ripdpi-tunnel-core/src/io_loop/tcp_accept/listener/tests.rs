use std::collections::HashMap;
use std::net::Ipv4Addr;

use smoltcp::iface::SocketSet;
use smoltcp::socket::tcp::Socket as TcpSocket;

use crate::io_loop::packet::build_ipv4_tcp_syn_packet;

use super::ensure_pending_listen_for_syn;

#[test]
fn pending_socket_uses_the_configured_capacity_in_both_directions() {
    for capacity in [1usize, 1024, 65_536, 16_777_216] {
        let mut pending = HashMap::new();
        let mut sockets = SocketSet::new(vec![]);
        let syn = build_ipv4_tcp_syn_packet(Ipv4Addr::new(10, 0, 0, 99), Ipv4Addr::new(203, 0, 113, 7), 40_000, 443);
        ensure_pending_listen_for_syn(&syn, &mut pending, &mut sockets, capacity);
        let handle = pending.values().next().expect("pending TCP socket").handle;
        let tcp = sockets.get::<TcpSocket>(handle);
        assert_eq!(tcp.recv_capacity(), capacity);
        assert_eq!(tcp.send_capacity(), capacity);
    }
}
