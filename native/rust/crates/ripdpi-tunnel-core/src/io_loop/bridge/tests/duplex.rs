use std::net::{IpAddr, Ipv4Addr, SocketAddr};

use smoltcp::iface::{Interface, SocketSet};
use smoltcp::socket::tcp::{self, Socket as TcpSocket};
use smoltcp::time::Instant;
use smoltcp::wire::IpAddress;
use tokio::io::{AsyncReadExt, AsyncWriteExt};

use crate::TunDevice;
use crate::io_loop::TCP_SOCKET_BUF;
use crate::io_loop::tcp_accept::{ensure_pending_listen_for_syn, socketaddr_to_listen_endpoint};

use super::super::{flush_pending_to_session, flush_pending_to_smoltcp, try_read_duplex, try_write_duplex};
use super::support::{build_ipv4_tcp_ack_packet, build_ipv4_tcp_syn_packet, tcp_seq_ack};

#[tokio::test]
async fn u10_try_read_duplex_returns_data() {
    let (mut a, mut b) = tokio::io::duplex(1024);
    b.write_all(b"hello").await.unwrap();
    // Allow the write to propagate
    tokio::task::yield_now().await;

    let mut buf = [0u8; 64];
    let result = try_read_duplex(&mut a, &mut buf);
    assert!(matches!(result, Some(Ok(5))));
    assert_eq!(&buf[..5], b"hello");
}

#[tokio::test]
async fn u11_try_read_duplex_pending_when_empty() {
    let (mut a, _b) = tokio::io::duplex(1024);
    let mut buf = [0u8; 64];
    let result = try_read_duplex(&mut a, &mut buf);
    assert!(result.is_none(), "expected Pending (None) when no data available");
}

#[tokio::test]
async fn u12_try_read_duplex_eof_on_closed() {
    let (mut a, b) = tokio::io::duplex(1024);
    drop(b);
    // Allow the drop to propagate
    tokio::task::yield_now().await;

    let mut buf = [0u8; 64];
    let result = try_read_duplex(&mut a, &mut buf);
    assert!(matches!(result, Some(Ok(0))), "expected EOF (0 bytes) when writer is dropped");
}

#[tokio::test]
async fn u13_try_write_duplex_returns_count() {
    let (mut a, _b) = tokio::io::duplex(1024);
    let result = try_write_duplex(&mut a, b"hello");
    assert!(matches!(result, Some(Ok(5))));
}

#[tokio::test]
async fn u14_try_write_duplex_pending_when_full() {
    let (mut a, _b) = tokio::io::duplex(16);
    // Fill the buffer
    let big = vec![0u8; 16];
    let _ = try_write_duplex(&mut a, &big);
    // Next write should return Pending
    let result = try_write_duplex(&mut a, &[1u8]);
    assert!(result.is_none(), "expected Pending (None) when buffer is full");
}

#[tokio::test]
async fn u15_flush_pending_to_session_drains_all() {
    let (mut a, mut b) = tokio::io::duplex(4096);
    let mut pending = vec![1u8; 100];
    let result = flush_pending_to_session(&mut a, &mut pending);
    assert!(matches!(result, Some(Ok(()))));
    assert!(pending.is_empty(), "pending should be fully drained");

    let mut recv = [0u8; 100];
    let n = b.read(&mut recv).await.unwrap();
    assert_eq!(n, 100);
    assert!(recv.iter().all(|&b| b == 1));
}

#[tokio::test]
async fn u16_flush_pending_to_session_partial_on_backpressure() {
    let (mut a, _b) = tokio::io::duplex(16);
    let mut pending = vec![2u8; 256];
    let result = flush_pending_to_session(&mut a, &mut pending);
    // Should return None (Pending) after partial drain, or Ok if it fit
    // With 16-byte buffer, 256 bytes won't fully fit
    assert!(result.is_none() || matches!(result, Some(Ok(()))));
    // If None, some data remains
    if result.is_none() {
        assert!(!pending.is_empty(), "pending should have remaining data when backpressured");
        assert!(pending.len() < 256, "some data should have been drained");
    }
}

#[tokio::test]
async fn u17_flush_pending_to_session_write_zero_errors() {
    let (mut a, b) = tokio::io::duplex(1024);
    drop(b);
    tokio::task::yield_now().await;

    let mut pending = vec![3u8; 10];
    let result = flush_pending_to_session(&mut a, &mut pending);
    // Closed stream should produce an error
    assert!(matches!(result, Some(Err(_))));
}

#[test]
fn u18_flush_pending_to_smoltcp_drains() {
    let mut device = TunDevice::new(1500);
    let config = smoltcp::iface::Config::new(smoltcp::wire::HardwareAddress::Ip);
    let mut iface = Interface::new(config, &mut device, Instant::now());
    iface.update_ip_addrs(|addrs| {
        addrs.push(smoltcp::wire::IpCidr::new(IpAddress::v4(10, 0, 0, 2), 24)).unwrap();
    });
    iface.routes_mut().add_default_ipv4_route(smoltcp::wire::Ipv4Address::new(10, 0, 0, 2)).expect("default route");
    iface.set_any_ip(true);

    let mut socket_set = SocketSet::new(vec![]);
    let mut pending_listens = std::collections::HashMap::new();

    let syn = build_ipv4_tcp_syn_packet(Ipv4Addr::new(10, 0, 0, 99), Ipv4Addr::new(127, 0, 0, 1), 51000, 443);
    ensure_pending_listen_for_syn(&syn, &mut pending_listens, &mut socket_set);
    device.rx_queue.push_back(syn);
    iface.poll(Instant::now(), &mut device, &mut socket_set);

    let syn_ack = device.tx_queue.pop_front().expect("syn-ack");
    let (server_seq, _) = tcp_seq_ack(&syn_ack);
    let ack = build_ipv4_tcp_ack_packet(
        Ipv4Addr::new(10, 0, 0, 99),
        Ipv4Addr::new(127, 0, 0, 1),
        51000,
        443,
        1,
        server_seq + 1,
    );
    device.rx_queue.push_back(ack);
    iface.poll(Instant::now(), &mut device, &mut socket_set);
    device.tx_queue.clear();

    let handle = pending_listens.values().next().map(|listener| listener.handle).expect("pending listen handle");

    let tcp = socket_set.get_mut::<TcpSocket>(handle);
    let mut pending = vec![42u8; 100];
    let result = flush_pending_to_smoltcp(tcp, &mut pending);
    assert!(result.is_ok());
    assert!(pending.is_empty(), "pending should be fully drained into smoltcp send buffer");
}

#[test]
fn u19_flush_pending_to_smoltcp_partial_on_full() {
    // Create a TCP socket with a tiny send buffer to force partial drain
    let mut device = TunDevice::new(1500);
    let config = smoltcp::iface::Config::new(smoltcp::wire::HardwareAddress::Ip);
    let mut iface = Interface::new(config, &mut device, Instant::now());
    iface.update_ip_addrs(|addrs| {
        addrs.push(smoltcp::wire::IpCidr::new(IpAddress::v4(10, 0, 0, 2), 24)).unwrap();
    });
    iface.routes_mut().add_default_ipv4_route(smoltcp::wire::Ipv4Address::new(10, 0, 0, 2)).expect("default route");
    iface.set_any_ip(true);

    let mut socket_set = SocketSet::new(vec![]);

    // Manually create a socket with tiny (32-byte) send buffer
    let mut socket =
        TcpSocket::new(tcp::SocketBuffer::new(vec![0u8; TCP_SOCKET_BUF]), tcp::SocketBuffer::new(vec![0u8; 32]));
    socket
        .listen(socketaddr_to_listen_endpoint(SocketAddr::new(IpAddr::V4(Ipv4Addr::new(127, 0, 0, 1)), 8443)))
        .expect("listen");
    let handle = socket_set.add(socket);

    // Drive through SYN -> SYN-ACK -> ACK handshake
    let syn = build_ipv4_tcp_syn_packet(Ipv4Addr::new(10, 0, 0, 99), Ipv4Addr::new(127, 0, 0, 1), 52000, 8443);
    device.rx_queue.push_back(syn);
    iface.poll(Instant::now(), &mut device, &mut socket_set);

    let syn_ack = device.tx_queue.pop_front().expect("syn-ack");
    let (server_seq, _) = tcp_seq_ack(&syn_ack);
    let ack = build_ipv4_tcp_ack_packet(
        Ipv4Addr::new(10, 0, 0, 99),
        Ipv4Addr::new(127, 0, 0, 1),
        52000,
        8443,
        1,
        server_seq + 1,
    );
    device.rx_queue.push_back(ack);
    iface.poll(Instant::now(), &mut device, &mut socket_set);
    device.tx_queue.clear();

    let tcp = socket_set.get_mut::<TcpSocket>(handle);
    assert_eq!(tcp.state(), tcp::State::Established);

    let mut pending = vec![7u8; 256];
    let result = flush_pending_to_smoltcp(tcp, &mut pending);
    // With a 32-byte send buffer, 256 bytes can't all fit -- send_slice returns 0 -> break
    assert!(result.is_ok());
    assert!(!pending.is_empty(), "with 32-byte send buffer, not all 256 bytes should fit");
}
