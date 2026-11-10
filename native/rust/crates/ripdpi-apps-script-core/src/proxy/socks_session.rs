use std::collections::HashMap;
use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Arc;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::Mutex;
use tokio::time::timeout;

use crate::config::LocalSocksAuth;
use crate::domain_fronter::AppsScriptDomainFronter;
use crate::mitm::MitmCertManager;
use crate::proxy::{HANDSHAKE_TIMEOUT, tunnel_dispatch};
use crate::socks5::{RelayTargetAddr, read_target, write_reply};
use crate::telemetry::SharedTelemetryState;

/// # Cancel safety:
///
/// NOT cancel-safe: the SOCKS5 handshake reads and writes fixed byte sequences
/// across multiple awaits; cancellation mid-handshake loses bytes already
/// consumed from the stream and may leave a partial reply written. The connection
/// is single-owned, so the only fallout is that one truncated session, but the
/// fn must not be polled in a select! arm expecting clean resumption.
pub(crate) async fn handle(
    mut stream: TcpStream,
    relay: Arc<AppsScriptDomainFronter>,
    mitm: Arc<Mutex<MitmCertManager>>,
    telemetry: SharedTelemetryState,
    hosts: HashMap<String, String>,
    local_socks_auth: Option<LocalSocksAuth>,
) -> io::Result<()> {
    let target = match timeout(HANDSHAKE_TIMEOUT, handshake(&mut stream, &telemetry, local_socks_auth.as_ref())).await {
        Ok(Ok(Some(target))) => target,
        Ok(Ok(None)) => return Ok(()),
        Ok(Err(error)) => return Err(error),
        Err(_) => return Err(io::Error::new(io::ErrorKind::TimedOut, "SOCKS5 handshake timed out")),
    };
    telemetry.record_target(&target.to_string());
    telemetry.session_opened();

    let result = async {
        write_reply(&mut stream, 0x00, SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0)).await?;
        let host = target.host();
        let port = target.port();
        tunnel_dispatch::dispatch(stream, &host, port, relay, mitm, telemetry.clone(), hosts).await
    }
    .await;

    telemetry.session_closed();
    result
}

/// Reads the SOCKS5 greeting and CONNECT request. Returns `None` when the
/// client asked for something this relay refuses; the refusal is already sent.
///
/// # Cancel safety:
///
/// NOT cancel-safe: reads and writes fixed byte sequences across awaits. The
/// caller drops the whole connection when its deadline cancels this.
async fn handshake(
    stream: &mut TcpStream,
    telemetry: &SharedTelemetryState,
    auth: Option<&LocalSocksAuth>,
) -> io::Result<Option<RelayTargetAddr>> {
    let mut greeting = [0u8; 2];
    stream.read_exact(&mut greeting).await?;
    if greeting[0] != 0x05 {
        return Ok(None);
    }

    let methods_len = usize::from(greeting[1]);
    let mut methods = vec![0u8; methods_len];
    stream.read_exact(&mut methods).await?;
    let required_method = if auth.is_some() { 0x02 } else { 0x00 };
    if !methods.contains(&required_method) {
        stream.write_all(&[0x05, 0xff]).await?;
        return Ok(None);
    }
    stream.write_all(&[0x05, required_method]).await?;
    if let Some(auth) = auth {
        verify_userpass(stream, auth).await?;
    }

    let mut request = [0u8; 4];
    stream.read_exact(&mut request).await?;
    if request[0] != 0x05 {
        return Ok(None);
    }
    if request[1] != 0x01 {
        write_reply(stream, 0x07, SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0)).await?;
        return Ok(None);
    }

    match read_target(stream, request[3]).await {
        Ok(target) => Ok(Some(target)),
        Err(error) => {
            telemetry.record_error(format!("invalid SOCKS5 target: {error}"));
            write_reply(stream, 0x08, SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0)).await?;
            Ok(None)
        }
    }
}

/// # Cancel safety:
/// NOT cancel-safe: partial credentials are consumed; the caller drops the socket on cancellation.
async fn verify_userpass(stream: &mut TcpStream, auth: &LocalSocksAuth) -> io::Result<()> {
    if stream.read_u8().await? != 1 {
        return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported SOCKS5 username/password version"));
    }
    let mut username = vec![0; usize::from(stream.read_u8().await?)];
    stream.read_exact(&mut username).await?;
    let mut password = vec![0; usize::from(stream.read_u8().await?)];
    stream.read_exact(&mut password).await?;
    let username_ok = constant_time_eq::constant_time_eq(&username, auth.username.as_bytes());
    let password_ok = constant_time_eq::constant_time_eq(&password, auth.password.as_bytes());
    if username_ok & password_ok {
        stream.write_all(&[1, 0]).await?;
        Ok(())
    } else {
        stream.write_all(&[1, 1]).await?;
        Err(io::Error::new(io::ErrorKind::PermissionDenied, "invalid local SOCKS credentials"))
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use tokio::net::TcpListener;

    use super::*;
    use crate::AppsScriptRuntimeConfig;
    use crate::telemetry::RelayTelemetryState;

    /// # Cancel safety:
    /// cancel-safe: the test owns and drops its sockets and handshake future.
    #[tokio::test(flavor = "current_thread", start_paused = true)]
    async fn silent_client_is_dropped_after_the_handshake_deadline() {
        let config =
            AppsScriptRuntimeConfig::from_json(r#"{"scriptIds":["script-id"],"authKey":"secret"}"#).expect("config");
        let dir = std::env::temp_dir().join(format!("ripdpi-socks-deadline-{}", std::process::id()));
        let mitm = Arc::new(Mutex::new(MitmCertManager::new_in(&dir).expect("MITM CA")));
        let telemetry = Arc::new(RelayTelemetryState::new(&config));
        let relay = Arc::new(AppsScriptDomainFronter::new(&config, telemetry.clone()));
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
        // No timer exists yet, so the paused clock cannot run ahead of the connect.
        let _client = TcpStream::connect(listener.local_addr().expect("address")).await.expect("connect");
        let (server, _) = listener.accept().await.expect("accept");

        // The client never sends a greeting; with no deadline this waits forever.
        let result = tokio::time::timeout(
            Duration::from_secs(3600),
            handle(server, relay, mitm, telemetry, HashMap::new(), None),
        )
        .await
        .expect("the handshake must end at its own deadline");
        assert_eq!(result.expect_err("silent client").kind(), io::ErrorKind::TimedOut);
        let _ = std::fs::remove_dir_all(&dir);
    }
    /// # Cancel safety:
    /// cancel-safe: the test owns both sockets and joins the handshake future.
    #[tokio::test(flavor = "current_thread")]
    async fn configured_credentials_negotiate_userpass() {
        let config = AppsScriptRuntimeConfig::from_json(
            r#"{"scriptIds":["script-id"],"authKey":"secret","localSocksUsername":"session-user","localSocksPassword":"session-pass"}"#,
        ).expect("config");
        let telemetry = Arc::new(RelayTelemetryState::new(&config));
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
        let mut client = TcpStream::connect(listener.local_addr().expect("address")).await.expect("connect");
        let (mut server, _) = listener.accept().await.expect("accept");
        let peer = async {
            client.write_all(&[5, 1, 2]).await.expect("greeting");
            let mut reply = [0; 2];
            client.read_exact(&mut reply).await.expect("method reply");
            assert_eq!(reply, [5, 2]);
            client.write_all(b"\x01\x0csession-user\x0csession-pass").await.expect("credentials");
            client.read_exact(&mut reply).await.expect("auth reply");
            assert_eq!(reply, [1, 0]);
            client.write_all(b"\x05\x01\x00\x03\x0borigin.test\x01\xbb").await.expect("CONNECT");
        };
        let ((), result) = tokio::join!(peer, handshake(&mut server, &telemetry, config.local_socks_auth.as_ref()));
        assert_eq!(result.expect("handshake"), Some(RelayTargetAddr::Domain("origin.test".into(), 443)));
    }

    /// # Cancel safety:
    /// cancel-safe: the test owns both sockets and the handshake future.
    #[tokio::test(flavor = "current_thread")]
    async fn configured_credentials_reject_downgrade_and_invalid_passwords() {
        let config = AppsScriptRuntimeConfig::from_json(
            r#"{"scriptIds":["script-id"],"authKey":"secret","localSocksUsername":"session-user","localSocksPassword":"session-pass"}"#,
        ).expect("config");
        let telemetry = Arc::new(RelayTelemetryState::new(&config));
        for (greeting, credentials) in [
            (&b"\x05\x01\x00"[..], None),
            (&b"\x05\x02\x00\x02"[..], Some(&b"\x01\x0csession-user\x05wrong"[..])),
            (&b"\x05\x01\x02"[..], Some(&b"\x01\x00\x00"[..])),
        ] {
            let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
            let mut client = TcpStream::connect(listener.local_addr().expect("address")).await.expect("connect");
            let (mut server, _) = listener.accept().await.expect("accept");
            let peer = async {
                client.write_all(greeting).await.expect("greeting");
                let mut reply = [0; 2];
                client.read_exact(&mut reply).await.expect("method reply");
                if let Some(credentials) = credentials {
                    assert_eq!(reply, [5, 2]);
                    client.write_all(credentials).await.expect("bad credentials");
                    client.read_exact(&mut reply).await.expect("auth reply");
                    assert_eq!(reply, [1, 1]);
                } else {
                    assert_eq!(reply, [5, 255]);
                }
            };
            let ((), result) = tokio::join!(peer, handshake(&mut server, &telemetry, config.local_socks_auth.as_ref()));
            assert!(!matches!(result, Ok(Some(_))), "rejected client reached CONNECT");
        }
    }

    /// # Cancel safety:
    /// NOT cancel-safe: cancellation before stop/join detaches the spawned runtime task;
    /// the test runtime owns and aborts that task when the test ends.
    #[tokio::test(flavor = "current_thread")]
    async fn runtime_listener_requires_credentials_before_http() {
        let dir = std::env::temp_dir().join(format!("ripdpi-auth-runtime-{}", std::process::id()));
        let mut config = AppsScriptRuntimeConfig::from_json(
            r#"{"scriptIds":["script-id"],"authKey":"secret","localSocksUsername":"u","localSocksPassword":"p"}"#,
        )
        .expect("config");
        config.listen_port = 0;
        config.data_dir = dir.clone();
        let runtime = crate::RelayRuntime::new(config);
        let running = runtime.clone();
        let task = tokio::spawn(async move { running.run().await });
        let address = timeout(Duration::from_secs(3), async {
            loop {
                if let Some(address) = runtime.telemetry().listener_address {
                    break address;
                }
                tokio::task::yield_now().await;
            }
        })
        .await
        .expect("listener readiness");
        let mut client = TcpStream::connect(address).await.expect("connect");
        client.write_all(&[5, 1, 2]).await.expect("greeting");
        let mut reply = [0; 2];
        client.read_exact(&mut reply).await.expect("method reply");
        assert_eq!(reply, [5, 2]);
        client.write_all(b"\x01\x01u\x01p").await.expect("credentials");
        client.read_exact(&mut reply).await.expect("auth reply");
        assert_eq!(reply, [1, 0]);
        client.write_all(b"\x05\x01\x00\x03\x0borigin.test\x00\x50").await.expect("CONNECT");
        let mut connect_reply = [0; 10];
        client.read_exact(&mut connect_reply).await.expect("CONNECT reply");
        assert_eq!(&connect_reply[..2], &[5, 0]);
        client
            .write_all(b"OPTIONS * HTTP/1.1\r\nHost: origin.test\r\nConnection: close\r\n\r\n")
            .await
            .expect("HTTP request");
        let mut response = String::new();
        timeout(Duration::from_secs(3), client.read_to_string(&mut response))
            .await
            .expect("HTTP deadline")
            .expect("HTTP response");
        assert!(response.starts_with("HTTP/1.1 501 "), "{response}");
        runtime.stop();
        timeout(Duration::from_secs(3), task).await.expect("stop deadline").expect("runtime task").expect("runtime");
        let _ = std::fs::remove_dir_all(dir);
    }
}
