use std::collections::HashMap;
use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Arc;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::Mutex;
use tokio::time::timeout;

use crate::domain_fronter::AppsScriptDomainFronter;
use crate::mitm::MitmCertManager;
use crate::proxy::{HANDSHAKE_TIMEOUT, tunnel_dispatch};
use crate::socks5::{RelayTargetAddr, read_target, write_reply};
use crate::telemetry::SharedTelemetryState;

/// # Cancel safety
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
) -> io::Result<()> {
    let target = match timeout(HANDSHAKE_TIMEOUT, handshake(&mut stream, &telemetry)).await {
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
/// # Cancel safety
///
/// NOT cancel-safe: reads and writes fixed byte sequences across awaits. The
/// caller drops the whole connection when its deadline cancels this.
async fn handshake(stream: &mut TcpStream, telemetry: &SharedTelemetryState) -> io::Result<Option<RelayTargetAddr>> {
    let mut greeting = [0u8; 2];
    stream.read_exact(&mut greeting).await?;
    if greeting[0] != 0x05 {
        return Ok(None);
    }

    let methods_len = usize::from(greeting[1]);
    let mut methods = vec![0u8; methods_len];
    stream.read_exact(&mut methods).await?;
    if !methods.contains(&0x00) {
        stream.write_all(&[0x05, 0xff]).await?;
        return Ok(None);
    }
    stream.write_all(&[0x05, 0x00]).await?;

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

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use tokio::net::TcpListener;

    use super::*;
    use crate::AppsScriptRuntimeConfig;
    use crate::telemetry::RelayTelemetryState;

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
        let result =
            tokio::time::timeout(Duration::from_secs(3600), handle(server, relay, mitm, telemetry, HashMap::new()))
                .await
                .expect("the handshake must end at its own deadline");
        assert_eq!(result.expect_err("silent client").kind(), io::ErrorKind::TimedOut);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
