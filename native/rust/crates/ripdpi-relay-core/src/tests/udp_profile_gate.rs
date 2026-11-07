use std::sync::Arc;
use std::time::Duration;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpStream, UdpSocket};

use super::sample_config;
use crate::config::RelayBackendConfig;
use crate::runtime::RelayRuntime;
use crate::runtime_validation::planned_backend_capabilities;

#[test]
fn disabled_udp_profiles_have_no_planned_udp_capability() {
    for kind in [
        "vless",
        "vless_reality",
        "hysteria2",
        "tuic_v5",
        "masque",
        "anytls",
        "trojan",
        "shadowsocks",
        "shadowtls_v3",
        "mieru",
        "ssh",
        "naiveproxy",
        "tor",
        "cloudflare_tunnel",
        "google_apps_script",
        "chain_relay",
        "obfs4",
        "snowflake",
        "webtunnel",
    ] {
        let mut config = sample_config(kind);
        if let RelayBackendConfig::Shadowsocks(shadowsocks) = &mut config.backend {
            shadowsocks.method = "2022-blake3-aes-128-gcm".to_owned();
        }
        assert!(!config.common.udp_enabled);
        assert!(!planned_backend_capabilities(&config).udp, "{kind} ignored disabled UDP");
    }
}

/// # Cancel safety:
/// NOT cancel-safe: dropping the test skips stop; the test runtime then aborts its local tasks.
#[tokio::test]
async fn disabled_udp_profile_rejects_associate_on_the_real_listener() {
    let upstream = UdpSocket::bind("127.0.0.1:0").await.expect("bind upstream observer");
    let mut config = sample_config("shadowsocks");
    config.common.server = "127.0.0.1".to_owned();
    config.common.server_port = i32::from(upstream.local_addr().expect("upstream address").port());
    config.common.local_socks_port = 0;
    let RelayBackendConfig::Shadowsocks(shadowsocks) = &mut config.backend else { unreachable!() };
    shadowsocks.method = "2022-blake3-aes-128-gcm".to_owned();
    shadowsocks.password = Some("AAECAwQFBgcICQoLDA0ODw==".to_owned());
    let runtime = RelayRuntime::new(config);
    let run = tokio::spawn(Arc::clone(&runtime).run());
    let observed = tokio::time::timeout(Duration::from_secs(3), async {
        let address = loop {
            if let Some(address) = runtime.telemetry().listener_address {
                break address;
            }
            assert!(!run.is_finished(), "relay failed before readiness");
            tokio::time::sleep(Duration::from_millis(5)).await;
        };
        let capability = runtime.telemetry().udp_capable;
        let mut stream = TcpStream::connect(address).await.expect("connect SOCKS listener");
        stream.write_all(&[5, 1, 0]).await.expect("send literal greeting");
        let mut greeting = [0; 2];
        stream.read_exact(&mut greeting).await.expect("read greeting reply");
        assert_eq!(greeting, [5, 0]);
        stream.write_all(&[5, 3, 0, 1, 0, 0, 0, 0, 0, 0]).await.expect("send literal ASSOCIATE");
        let mut reply = [0; 10];
        stream.read_exact(&mut reply).await.expect("read ASSOCIATE reply");
        let mut packet = [0; 64];
        let carrier_activity = tokio::time::timeout(Duration::from_millis(30), upstream.recv_from(&mut packet)).await;
        (capability, reply, carrier_activity.is_ok())
    })
    .await;
    runtime.stop();
    tokio::time::timeout(Duration::from_secs(3), run)
        .await
        .expect("runtime stop deadline")
        .expect("join runtime")
        .expect("runtime result");
    let (capability, reply, carrier_activity) = observed.expect("wire exchange deadline");
    assert_eq!(reply, [5, 7, 0, 1, 0, 0, 0, 0, 0, 0]);
    assert_eq!(capability, Some(false), "running telemetry ignored disabled UDP");
    assert!(!carrier_activity, "disabled UDP emitted a carrier datagram");
}
