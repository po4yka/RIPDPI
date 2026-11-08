use super::association_state::{OutboundDatagram, UdpAssociation};
use super::event_handling::UdpEvent;
use super::worker::spawn_udp_association;
use crate::session::udp::UdpMemoryBudget;
use crate::session::{Auth, TargetAddr};
use std::net::SocketAddr;
use std::time::Duration;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpListener, TcpStream, UdpSocket};
use tokio_util::sync::CancellationToken;

/// # Cancel safety:
/// cancel-safe: the handshake owns and discards its accepted socket.
async fn handshake(listener: &TcpListener, relay: SocketAddr) -> TcpStream {
    let (mut control, _) = listener.accept().await.unwrap();
    let mut greeting = [0; 3];
    control.read_exact(&mut greeting).await.unwrap();
    assert_eq!(greeting, [5, 1, 0]);
    control.write_all(&[5, 0]).await.unwrap();
    let mut request = [0; 10];
    control.read_exact(&mut request).await.unwrap();
    assert_eq!(request, [5, 3, 0, 1, 0, 0, 0, 0, 0, 0]);
    let port = relay.port().to_be_bytes();
    control.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, port[0], port[1]]).await.unwrap();
    control
}

fn worker(
    proxy: SocketAddr,
    id: u64,
    budget: &UdpMemoryBudget,
) -> (UdpAssociation, tokio::sync::mpsc::Receiver<UdpEvent>) {
    let (tx, rx) = tokio::sync::mpsc::channel(4);
    let association = spawn_udp_association(
        proxy,
        Auth::NoAuth,
        "10.0.0.2:53001".parse().unwrap(),
        "203.0.113.9:53".parse().unwrap(),
        id,
        Duration::from_secs(60),
        Duration::from_secs(2),
        None,
        budget.clone(),
        CancellationToken::new(),
        tx,
    );
    (association, rx)
}

/// # Cancel safety:
/// NOT cancel-safe: cancellation detaches server or association tasks until test runtime teardown.
#[tokio::test]
async fn loc09_closed_control_retires_worker_and_later_traffic_uses_fresh_binding() {
    let _guard = crate::PROTECT_CALLBACK_TEST_LOCK.lock().await;
    let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let proxy = listener.local_addr().unwrap();
    let old_relay = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let new_relay = UdpSocket::bind("127.0.0.1:0").await.unwrap();
    let old_addr = old_relay.local_addr().unwrap();
    let new_addr = new_relay.local_addr().unwrap();
    let server = tokio::spawn(async move {
        let control = handshake(&listener, old_addr).await;
        drop(control);
        let _control = handshake(&listener, new_addr).await;
        let mut packet = [0; 64];
        let (len, _) =
            tokio::time::timeout(Duration::from_secs(2), new_relay.recv_from(&mut packet)).await.unwrap().unwrap();
        packet[..len].to_vec()
    });
    let budget = UdpMemoryBudget::for_tunnel_mtu(1500);
    let (old, mut events) = worker(proxy, 1, &budget);
    let event = tokio::time::timeout(Duration::from_secs(1), events.recv()).await.unwrap();
    assert!(matches!(event, Some(UdpEvent::Closed { association_id: 1, .. })));
    tokio::time::timeout(Duration::from_secs(1), old.worker).await.unwrap().unwrap();
    let (new, _events) = worker(proxy, 2, &budget);
    let target = "203.0.113.9:53".parse().unwrap();
    new.outbound
        .send(OutboundDatagram::try_new(TargetAddr::Ip(target), target, target, b"fresh", &budget).unwrap())
        .await
        .unwrap();
    assert_eq!(server.await.unwrap(), b"\0\0\0\x01\xcb\0\x71\x09\0\x35fresh");
    assert!(
        matches!(old_relay.try_recv_from(&mut [0; 64]), Err(error) if error.kind() == std::io::ErrorKind::WouldBlock)
    );
    new.cancel.cancel();
    tokio::time::timeout(Duration::from_secs(1), new.worker).await.unwrap().unwrap();
}
