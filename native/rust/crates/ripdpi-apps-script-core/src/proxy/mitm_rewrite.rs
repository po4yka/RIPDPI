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

/// # Cancel safety
///
/// NOT cancel-safe: performs a multi-step TLS handshake (ClientHello sniff +
/// into_stream) then loops over handle_request, which partial-reads/writes the
/// HTTP exchange. Cancellation can leave the handshake or a response truncated.
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

    let server_config = MitmCertManager::get_server_config(&mitm, &effective_host)
        .await
        .map_err(|error| io::Error::other(error.to_string()))?;
    let mut tls_stream = acceptor
        .into_stream(server_config)
        .await
        .map_err(|error| io::Error::other(format!("TLS accept failed: {error}")))?;

    let mut pending = Vec::new();
    loop {
        match http_relay::handle_request(&mut tls_stream, &mut pending, &effective_host, port, "https", relay.as_ref())
            .await?
        {
            true => continue,
            false => return Ok(()),
        }
    }
}

/// # Cancel safety
///
/// NOT cancel-safe: the TLS accept and outbound connect are multi-step awaits
/// preceding the copy_bidirectional; cancellation during the handshake leaves a
/// partially negotiated TLS session. (The steady-state copy phase alone would be
/// cancel-safe, but the fn as a whole is not.)
pub(crate) async fn sni_rewrite_tunnel(
    stream: TcpStream,
    host: &str,
    port: u16,
    upstream: String,
    relay: Arc<AppsScriptDomainFronter>,
    mitm: Arc<Mutex<MitmCertManager>>,
    telemetry: SharedTelemetryState,
) -> io::Result<()> {
    let server_config =
        MitmCertManager::get_server_config(&mitm, host).await.map_err(|error| io::Error::other(error.to_string()))?;
    let inbound = TlsAcceptor::from(server_config)
        .accept(stream)
        .await
        .map_err(|error| io::Error::other(format!("TLS MITM accept failed: {error}")))?;
    // The client's bytes are decrypted here, so the upstream leg must be TLS again.
    let mut outbound = match relay.open_rewrite_stream(&upstream, port).await {
        Ok(outbound) => outbound,
        Err(error) => {
            let message = format!("SNI-rewrite upstream TLS failed: {error}");
            telemetry.record_error(message.clone());
            return Err(io::Error::other(message));
        }
    };
    let mut inbound = inbound;
    let _ = tokio::io::copy_bidirectional(&mut inbound, &mut outbound).await?;
    telemetry.record_success(0);
    Ok(())
}

fn looks_like_ip(value: &str) -> bool {
    value.parse::<IpAddr>().is_ok()
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;
    use tokio::time::timeout;
    use tokio_rustls::TlsConnector;
    use tokio_rustls::rustls::pki_types::pem::PemObject;
    use tokio_rustls::rustls::pki_types::{CertificateDer, ServerName};
    use tokio_rustls::rustls::{ClientConfig, RootCertStore};

    use super::*;
    use crate::AppsScriptRuntimeConfig;
    use crate::telemetry::RelayTelemetryState;

    const FRONT_DOMAIN: &str = "www.google.com";

    #[tokio::test]
    async fn sni_rewrite_upstream_leg_is_tls_with_front_domain_sni() {
        let dir = std::env::temp_dir().join(format!("ripdpi-sni-rewrite-test-{}", std::process::id()));
        let _ = std::fs::remove_dir_all(&dir);
        let mitm = Arc::new(Mutex::new(MitmCertManager::new_in(&dir).expect("MITM CA")));
        let ca = CertificateDer::from_pem_file(dir.join("relay-ca/ca.crt")).expect("CA certificate");
        let config = AppsScriptRuntimeConfig::from_json(&format!(
            r#"{{"scriptIds":["script-id"],"authKey":"secret","frontDomain":"{FRONT_DOMAIN}"}}"#
        ))
        .expect("config");
        let telemetry = Arc::new(RelayTelemetryState::new(&config));
        let relay = Arc::new(AppsScriptDomainFronter::new(&config));

        let upstream = TcpListener::bind("127.0.0.1:0").await.expect("bind upstream");
        let upstream_port = upstream.local_addr().expect("upstream addr").port();
        let front = TcpListener::bind("127.0.0.1:0").await.expect("bind front");
        let front_addr = front.local_addr().expect("front addr");
        tokio::spawn(async move {
            let (stream, _) = front.accept().await.expect("accept client");
            let upstream = "127.0.0.1".to_string();
            let _ =
                sni_rewrite_tunnel(stream, "www.youtube.com", upstream_port, upstream, relay, mitm, telemetry).await;
        });

        let mut roots = RootCertStore::empty();
        roots.add(ca).expect("trust MITM CA");
        let client_config = ClientConfig::builder().with_root_certificates(roots).with_no_client_auth();
        let client = tokio::spawn(async move {
            let tcp = TcpStream::connect(front_addr).await.expect("connect front");
            let name = ServerName::try_from("www.youtube.com").expect("name");
            let mut tls = TlsConnector::from(Arc::new(client_config)).connect(name, tcp).await.expect("MITM TLS");
            tls.write_all(b"GET / HTTP/1.1\r\nCookie: session=plain-secret\r\n\r\n").await.expect("write request");
            tls.flush().await.expect("flush request");
            tls
        });

        let (mut wire, _) =
            timeout(Duration::from_secs(5), upstream.accept()).await.expect("upstream dial").expect("accept");
        let mut first = vec![0u8; 4096];
        let read = timeout(Duration::from_secs(5), wire.read(&mut first)).await.expect("upstream bytes").expect("read");
        let first = &first[..read];
        assert!(
            !first.windows(12).any(|window| window == b"plain-secret"),
            "decrypted request must not reach the wire"
        );
        assert_eq!(
            ripdpi_packets::parse_tls(first),
            Some(FRONT_DOMAIN.as_bytes()),
            "upstream leg must be TLS to the front"
        );
        drop(client);
        let _ = std::fs::remove_dir_all(&dir);
    }
}
