use std::sync::Mutex;

use super::*;

fn doq_server() -> (quinn::Endpoint, CertificateDer<'static>) {
    let certificate = generate_simple_self_signed(vec!["localhost".into()]).expect("certificate");
    let cert = certificate.cert.der().clone();
    let mut tls = ServerConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .expect("ring provider supports default TLS versions")
        .with_no_client_auth()
        .with_single_cert(vec![cert.clone()], PrivatePkcs8KeyDer::from(certificate.signing_key.serialize_der()).into())
        .expect("server config");
    tls.alpn_protocols = vec![b"doq".to_vec()];
    let crypto = quinn::crypto::rustls::QuicServerConfig::try_from(tls).expect("QUIC server config");
    let server =
        quinn::Endpoint::server(quinn::ServerConfig::with_crypto(Arc::new(crypto)), (Ipv4Addr::LOCALHOST, 0).into())
            .expect("DoQ server");
    (server, cert)
}

/// Forwards datagrams between the client and `server`, recording the size of
/// every datagram the client sends.
async fn spawn_recording_udp_relay(server: std::net::SocketAddr) -> (u16, Arc<Mutex<Vec<usize>>>) {
    let relay = tokio::net::UdpSocket::bind((Ipv4Addr::LOCALHOST, 0)).await.expect("relay socket");
    let port = relay.local_addr().expect("relay address").port();
    let sizes = Arc::new(Mutex::new(Vec::new()));
    let recorded = sizes.clone();
    tokio::spawn(async move {
        let mut client = None;
        let mut buf = vec![0u8; 65_535];
        while let Ok((len, from)) = relay.recv_from(&mut buf).await {
            let to = if from == server {
                let Some(client) = client else { continue };
                client
            } else {
                client = Some(from);
                recorded.lock().expect("sizes lock").push(len);
                server
            };
            let _ = relay.send_to(&buf[..len], to).await;
        }
    });
    (port, sizes)
}

#[tokio::test(flavor = "current_thread")]
async fn doq_pads_every_client_datagram_to_the_mtu() {
    let (server, cert) = doq_server();
    let (port, sizes) = spawn_recording_udp_relay(server.local_addr().expect("server address")).await;
    let resolver = EncryptedDnsResolver::with_extra_tls_roots(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doq,
            resolver_id: None,
            host: "localhost".into(),
            port,
            tls_server_name: None,
            bootstrap_ips: vec![Ipv4Addr::LOCALHOST.into()],
            doh_url: None,
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
            odoh: None,
        },
        EncryptedDnsTransport::Direct,
        Duration::from_secs(2),
        vec![cert],
    )
    .expect("resolver builds");
    let query = build_query("localhost");
    let serve = async {
        let connection = server.accept().await.expect("incoming").await.expect("handshake");
        let (mut send, mut recv) = connection.accept_bi().await.expect("query stream");
        let mut length = [0; 2];
        recv.read_exact(&mut length).await.expect("query length");
        let mut received = vec![0; usize::from(u16::from_be_bytes(length))];
        recv.read_exact(&mut received).await.expect("query body");
        let reply = build_empty_response(&received);
        send.write_all(&u16::try_from(reply.len()).expect("reply fits").to_be_bytes()).await.expect("reply length");
        send.write_all(&reply).await.expect("reply body");
        send.finish().expect("finish reply");
        let _ = send.stopped().await;
    };

    let ((), result) =
        tokio::time::timeout(Duration::from_secs(5), async { tokio::join!(serve, resolver.exchange(&query)) })
            .await
            .expect("exchange finishes");

    result.expect("DoQ exchange through the relay");
    let sizes = sizes.lock().expect("sizes lock").clone();
    assert!(sizes.len() > 1, "sizes: {sizes:?}");
    // RFC 9250 5.4: the queries carry no EDNS(0) padding, so QUIC padding must hide their size.
    assert!(sizes.iter().all(|&size| size >= 1_200), "unpadded client datagrams: {sizes:?}");
}
