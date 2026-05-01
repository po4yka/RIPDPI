use std::collections::HashMap;
use std::io;
use std::sync::Arc;
use std::time::Duration;

use tokio::net::TcpStream;
use tokio::sync::Mutex;
use tokio::time::timeout;

use crate::domain_fronter::AppsScriptDomainFronter;
use crate::mitm::MitmCertManager;
use crate::proxy::{http_relay, mitm_rewrite};
use crate::telemetry::SharedTelemetryState;

const FIRST_BYTES_TIMEOUT: Duration = Duration::from_millis(300);

pub(crate) async fn dispatch(
    stream: TcpStream,
    host: &str,
    port: u16,
    relay: Arc<AppsScriptDomainFronter>,
    mitm: Arc<Mutex<MitmCertManager>>,
    telemetry: SharedTelemetryState,
    hosts: HashMap<String, String>,
) -> io::Result<()> {
    if let Some(upstream) = hosts_override(&hosts, host).map(ToOwned::to_owned) {
        return mitm_rewrite::sni_rewrite_tunnel(stream, host, port, upstream, mitm, telemetry).await;
    }

    let mut peek = [0u8; 8];
    let peeked = match timeout(FIRST_BYTES_TIMEOUT, stream.peek(&mut peek)).await {
        Ok(Ok(bytes)) => bytes,
        Ok(Err(error)) => return Err(error),
        Err(_) => {
            plain_tcp_passthrough(stream, host, port).await?;
            return Ok(());
        }
    };

    if peeked >= 1 && peek[0] == 0x16 {
        mitm_rewrite::mitm_then_relay(stream, host, port, relay, mitm, telemetry).await?;
        return Ok(());
    }

    if peeked > 0 && http_relay::looks_like_http(&peek[..peeked]) {
        http_relay::relay_raw(stream, host, port, relay, telemetry).await?;
        return Ok(());
    }

    plain_tcp_passthrough(stream, host, port).await
}

pub(crate) async fn plain_tcp_passthrough(mut inbound: TcpStream, host: &str, port: u16) -> io::Result<()> {
    let outbound = TcpStream::connect((host, port)).await?;
    outbound.set_nodelay(true)?;
    inbound.set_nodelay(true)?;
    let mut outbound = outbound;
    let _ = tokio::io::copy_bidirectional(&mut inbound, &mut outbound).await?;
    Ok(())
}

fn hosts_override<'a>(hosts: &'a HashMap<String, String>, host: &str) -> Option<&'a str> {
    let host = host.to_ascii_lowercase();
    if let Some(address) = hosts.get(&host) {
        return Some(address.as_str());
    }
    let parts: Vec<&str> = host.split('.').collect();
    for start in 1..parts.len() {
        let parent = parts[start..].join(".");
        if let Some(address) = hosts.get(&parent) {
            return Some(address.as_str());
        }
    }
    None
}
