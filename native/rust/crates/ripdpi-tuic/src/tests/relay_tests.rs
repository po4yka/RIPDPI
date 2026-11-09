//! Regression tests for client behaviour against a loopback TUIC v5 fixture.

use super::*;

fn packet_frame(assoc_id: u16, packet_id: u16, address: TuicAddress, payload: &[u8]) -> bytes::Bytes {
    let header = PacketHeader {
        assoc_id,
        packet_id,
        fragment_total: 1,
        fragment_id: 0,
        payload_len: payload.len() as u16,
        address,
    };
    let mut frame = BytesMut::with_capacity(header.encoded_len() + payload.len());
    header.encode(&mut frame);
    frame.extend_from_slice(payload);
    frame.freeze()
}

/// TUIC v5 SPEC "UDP relaying": one association is one server UDP socket
/// (full cone), so every destination of a session shares one ASSOC_ID, and
/// the server may deliver a packet from any source on it.
#[tokio::test]
async fn udp_session_uses_one_association_for_all_destinations() {
    let (server, cert_der) = fixture_server();
    let server_addr = server.local_addr().expect("server addr");
    let server_task = tokio::spawn(async move {
        let connection = server.accept().await.expect("incoming").await.expect("connection");
        let mut seen = Vec::new();
        for _ in 0..3 {
            let datagram = read_packet_datagram(&connection).await;
            let (header, _) = PacketHeader::decode(&datagram).expect("decode packet");
            seen.push((header.assoc_id, header.packet_id));
        }
        let reply = packet_frame(seen[0].0, 0, TuicAddress::Domain("third.example".to_owned(), 53), b"pong");
        connection.send_datagram(reply).expect("send reply");
        connection.closed().await;
        seen
    });

    let client = fixture_client(server_addr, cert_der, false).await;
    let mut udp = client.udp_session().await.expect("udp session");
    for destination in ["one.example:53", "two.example:53", "one.example:53"] {
        udp.send_to(destination, b"ping").await.expect("udp send");
    }
    let (source, payload) = tokio::time::timeout(Duration::from_secs(5), udp.recv_from())
        .await
        .expect("reply from an unrelated source must arrive")
        .expect("udp recv");
    assert_eq!((source.as_str(), payload.as_slice()), ("third.example:53", b"pong".as_slice()));
    drop((udp, client));

    let seen = server_task.await.expect("server task");
    assert!(seen.iter().all(|(assoc_id, _)| *assoc_id == seen[0].0), "one ASSOC_ID per session: {seen:?}");
    let packet_ids: Vec<u16> = seen.iter().map(|(_, packet_id)| *packet_id).collect();
    assert_eq!(packet_ids, [0, 1, 2], "one PKT_ID counter per association");
}

/// A UDP session on a closed connection could only fail later, silently, at
/// the first send; `udp_session` must report the close up front.
#[tokio::test]
async fn udp_session_fails_once_the_connection_is_closed() {
    let (server, cert_der) = fixture_server();
    let server_addr = server.local_addr().expect("server addr");
    let server_task = tokio::spawn(async move {
        let connection = server.accept().await.expect("incoming").await.expect("connection");
        connection.close(quinn::VarInt::from_u32(7), b"bye");
        connection.closed().await;
    });
    let client = fixture_client(server_addr, cert_der, false).await;
    server_task.await.expect("server task");

    // The client learns of the close asynchronously; once it has, no session opens.
    let deadline = tokio::time::Instant::now() + Duration::from_secs(5);
    let error = loop {
        match client.udp_session().await {
            Err(error) => break error,
            Ok(_) if tokio::time::Instant::now() < deadline => tokio::time::sleep(Duration::from_millis(10)).await,
            Ok(_) => panic!("a closed connection must not yield a UDP session"),
        }
    };
    assert_eq!(error.kind(), std::io::ErrorKind::BrokenPipe);
    assert!(error.to_string().contains("closed"), "the error must name the close: {error}");
}
