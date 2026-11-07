use std::net::{Ipv4Addr, SocketAddr};
use std::sync::Arc;
use std::time::Duration;

use local_network_fixture::ShadowsocksLoopback;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpStream, UdpSocket};

use super::sample_config;
use crate::config::RelayBackendConfig;
use crate::runtime::RelayRuntime;

/// # Cancel safety:
/// NOT cancel-safe: dropping the test skips stop; the test runtime then aborts its local tasks.
#[tokio::test]
async fn ss2022_oversized_datagram_does_not_end_the_socks_association() {
    for (method, credential) in [
        ("2022-blake3-aes-128-gcm", "AAECAwQFBgcICQoLDA0ODw=="),
        ("2022-blake3-aes-128-gcm", "AAECAwQFBgcICQoLDA0ODw==:EBESExQVFhcYGRobHB0eHw=="),
        ("2022-blake3-aes-256-gcm", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="),
        ("2022-blake3-chacha20-poly1305", "AAECAwQFBgcICQoLDA0ODxAREhMUFRYXGBkaGxwdHh8="),
    ] {
        let fixture = ShadowsocksLoopback::start(method, credential).await.expect("start SS2022 fixture");
        let mut config = sample_config("shadowsocks");
        config.common.server = "127.0.0.1".to_owned();
        config.common.server_port = i32::from(fixture.port());
        config.common.local_socks_port = 0;
        config.common.udp_enabled = true;
        let RelayBackendConfig::Shadowsocks(shadowsocks) = &mut config.backend else { unreachable!() };
        shadowsocks.method = method.to_owned();
        shadowsocks.password = Some(credential.to_owned());
        let runtime = RelayRuntime::new(config);
        let run = tokio::spawn(Arc::clone(&runtime).run());
        let exchange = tokio::time::timeout(Duration::from_secs(3), async {
            let listener = loop {
                if let Some(listener) = runtime.telemetry().listener_address {
                    break listener;
                }
                assert!(!run.is_finished(), "relay failed before readiness");
                tokio::time::sleep(Duration::from_millis(5)).await;
            };
            let mut control = TcpStream::connect(listener).await.expect("connect SOCKS listener");
            control.write_all(&[5, 1, 0]).await.expect("send greeting");
            let mut greeting = [0; 2];
            control.read_exact(&mut greeting).await.expect("read greeting");
            assert_eq!(greeting, [5, 0]);
            control.write_all(&[5, 3, 0, 1, 0, 0, 0, 0, 0, 0]).await.expect("send ASSOCIATE");
            let mut reply = [0; 10];
            control.read_exact(&mut reply).await.expect("read ASSOCIATE reply");
            assert_eq!(&reply[..4], &[5, 0, 0, 1]);
            let relay = SocketAddr::from((
                Ipv4Addr::new(reply[4], reply[5], reply[6], reply[7]),
                u16::from_be_bytes([reply[8], reply[9]]),
            ));
            let socket = UdpSocket::bind("127.0.0.1:0").await.expect("bind SOCKS client");
            let mut header = vec![0, 0, 0, 1, 127, 0, 0, 1];
            header.extend_from_slice(&fixture.udp_target_port().to_be_bytes());
            let mut small = header.clone();
            small.extend_from_slice(b"before-size-error");
            socket.send_to(&small, relay).await.expect("send first small datagram");
            let mut response = vec![0; 65_535];
            let received = socket.recv(&mut response).await.expect("receive first small datagram");
            assert_eq!(&response[..received], small);
            // A legal client frame grows beyond the outer UDP ceiling after SS2022 encryption.
            let wire_limit = if cfg!(target_os = "macos") { 9_216 } else { 65_507 };
            let mut large = header.clone();
            large.resize(wire_limit, 0x7a);
            socket.send_to(&large, relay).await.expect("send oversized carrier input");
            let mut after = header;
            after.extend_from_slice(b"after-size-error");
            socket.send_to(&after, relay).await.expect("send second small datagram");
            let received = socket.recv(&mut response).await.expect("receive second small datagram");
            assert_eq!(&response[..received], after);
            let mut probe = [0; 1];
            assert!(
                tokio::time::timeout(Duration::from_millis(30), control.read(&mut probe)).await.is_err(),
                "one oversized datagram closed the TCP control connection"
            );
        })
        .await;
        runtime.stop();
        tokio::time::timeout(Duration::from_secs(3), run)
            .await
            .expect("runtime stop deadline")
            .expect("join runtime")
            .expect("runtime result");
        exchange.unwrap_or_else(|error| panic!("{method} lost association after one large datagram: {error}"));
    }
}
