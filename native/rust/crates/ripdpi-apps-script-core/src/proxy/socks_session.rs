use std::collections::HashMap;
use std::io;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::Arc;

use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::Mutex;

use crate::domain_fronter::AppsScriptDomainFronter;
use crate::mitm::MitmCertManager;
use crate::proxy::tunnel_dispatch;
use crate::socks5::{read_target, write_reply};
use crate::telemetry::SharedTelemetryState;

// NOT cancel-safe: the SOCKS5 handshake reads and writes fixed byte sequences
// across multiple awaits; cancellation mid-handshake loses bytes already
// consumed from the stream and may leave a partial reply written. The connection
// is single-owned, so the only fallout is that one truncated session, but the
// fn must not be polled in a select! arm expecting clean resumption.
pub(crate) async fn handle(
    mut stream: TcpStream,
    relay: Arc<AppsScriptDomainFronter>,
    mitm: Arc<Mutex<MitmCertManager>>,
    telemetry: SharedTelemetryState,
    hosts: HashMap<String, String>,
) -> io::Result<()> {
    let mut greeting = [0u8; 2];
    stream.read_exact(&mut greeting).await?;
    if greeting[0] != 0x05 {
        return Ok(());
    }

    let methods_len = usize::from(greeting[1]);
    let mut methods = vec![0u8; methods_len];
    stream.read_exact(&mut methods).await?;
    if !methods.contains(&0x00) {
        stream.write_all(&[0x05, 0xff]).await?;
        return Ok(());
    }
    stream.write_all(&[0x05, 0x00]).await?;

    let mut request = [0u8; 4];
    stream.read_exact(&mut request).await?;
    if request[0] != 0x05 {
        return Ok(());
    }
    if request[1] != 0x01 {
        write_reply(&mut stream, 0x07, SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0)).await?;
        return Ok(());
    }

    let target = match read_target(&mut stream, request[3]).await {
        Ok(target) => target,
        Err(error) => {
            telemetry.record_error(format!("invalid SOCKS5 target: {error}"));
            write_reply(&mut stream, 0x08, SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0)).await?;
            return Ok(());
        }
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
