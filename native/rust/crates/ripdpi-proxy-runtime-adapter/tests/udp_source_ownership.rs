use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr, UdpSocket};
use std::time::Duration;

use ripdpi_proxy_runtime_adapter::platform::udp::build_udp_upstream_socket;
use socket2::{Domain, Protocol, Socket, Type};

#[test]
fn upstream_udp_socket_keeps_exclusive_source_ownership() {
    for ip in [IpAddr::V4(Ipv4Addr::LOCALHOST), IpAddr::V6(Ipv6Addr::LOCALHOST)] {
        for bind_low_port in [false, true] {
            let peer = UdpSocket::bind(SocketAddr::new(ip, 0)).expect("peer socket");
            peer.set_read_timeout(Some(Duration::from_secs(1))).expect("peer timeout");
            let upstream = build_udp_upstream_socket(peer.local_addr().expect("peer address"), None, bind_low_port)
                .expect("upstream socket");
            assert!(
                !socket2::SockRef::from(&upstream).reuse_address().expect("read upstream reuse-address"),
                "flow-owned UDP sockets must reserve their source address exclusively"
            );
            let source = upstream.local_addr().expect("upstream source");
            let competitor =
                Socket::new(Domain::for_address(source), Type::DGRAM, Some(Protocol::UDP)).expect("competing socket");
            competitor.set_reuse_address(true).expect("competing reuse-address");
            let rebound = competitor.bind(&source.into());
            if cfg!(any(target_os = "linux", target_os = "android")) {
                assert!(rebound.is_err(), "another socket must not bind live source {source}");
            }

            upstream.send(b"owner-request").expect("send request");
            let mut bytes = [0; 32];
            let (size, sender) = peer.recv_from(&mut bytes).expect("receive request");
            assert_eq!(&bytes[..size], b"owner-request");
            assert_eq!(sender, source);
            peer.send_to(b"owner-response", sender).expect("send response");
            upstream.set_nonblocking(false).expect("blocking test receive");
            let size = upstream.recv(&mut bytes).expect("receive owner response");
            assert_eq!(&bytes[..size], b"owner-response");
        }
    }
}
