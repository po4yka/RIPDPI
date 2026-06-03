use std::io;
use std::net::IpAddr;
use std::sync::Arc;

use tokio::net::TcpStream;
use tokio::sync::Mutex;
use tokio_rustls::rustls::server::Acceptor;
use tokio_rustls::{LazyConfigAcceptor, TlsAcceptor};

use crate::domain_fronter::AppsScriptDomainFronter;
use crate::mitm::MitmCertManager;
use crate::proxy::http_relay;
use crate::telemetry::SharedTelemetryState;

// NOT cancel-safe: performs a multi-step TLS handshake (ClientHello sniff +
// into_stream) then loops over handle_request, which partial-reads/writes the
// HTTP exchange. Cancellation can leave the handshake or a response truncated.
pub(crate) async fn mitm_then_relay(
    stream: TcpStream,
    host: &str,
    port: u16,
    relay: Arc<AppsScriptDomainFronter>,
    mitm: Arc<Mutex<MitmCertManager>>,
    telemetry: SharedTelemetryState,
) -> io::Result<()> {
    let acceptor = LazyConfigAcceptor::new(Acceptor::default(), stream)
        .await
        .map_err(|error| io::Error::other(format!("client hello sniff failed: {error}")))?;
    let sni_host = acceptor.client_hello().server_name().filter(|value| !looks_like_ip(value)).map(ToOwned::to_owned);
    let effective_host = sni_host.as_deref().unwrap_or(host).to_string();
    telemetry.record_target(&format!("{effective_host}:{port}"));

    let server_config = {
        let mut manager = mitm.lock().await;
        manager.get_server_config(&effective_host).map_err(|error| io::Error::other(error.to_string()))?
    };
    let mut tls_stream = acceptor
        .into_stream(server_config)
        .await
        .map_err(|error| io::Error::other(format!("TLS accept failed: {error}")))?;

    loop {
        match http_relay::handle_request(&mut tls_stream, &effective_host, port, "https", relay.as_ref()).await? {
            true => continue,
            false => return Ok(()),
        }
    }
}

// NOT cancel-safe: the TLS accept and outbound connect are multi-step awaits
// preceding the copy_bidirectional; cancellation during the handshake leaves a
// partially negotiated TLS session. (The steady-state copy phase alone would be
// cancel-safe, but the fn as a whole is not.)
pub(crate) async fn sni_rewrite_tunnel(
    stream: TcpStream,
    host: &str,
    port: u16,
    upstream: String,
    mitm: Arc<Mutex<MitmCertManager>>,
    telemetry: SharedTelemetryState,
) -> io::Result<()> {
    let server_config = {
        let mut manager = mitm.lock().await;
        manager.get_server_config(host).map_err(|error| io::Error::other(error.to_string()))?
    };
    let inbound = TlsAcceptor::from(server_config)
        .accept(stream)
        .await
        .map_err(|error| io::Error::other(format!("TLS MITM accept failed: {error}")))?;
    let mut outbound = TcpStream::connect((upstream, port)).await?;
    let _ = outbound.set_nodelay(true);
    let mut inbound = inbound;
    let _ = tokio::io::copy_bidirectional(&mut inbound, &mut outbound).await?;
    telemetry.record_success(0);
    Ok(())
}

fn looks_like_ip(value: &str) -> bool {
    value.parse::<IpAddr>().is_ok()
}
