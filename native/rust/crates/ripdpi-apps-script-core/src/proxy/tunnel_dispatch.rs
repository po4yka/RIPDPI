use std::collections::HashMap;
use std::io;
use std::sync::Arc;
use std::time::Duration;

use tokio::net::TcpStream;
use tokio::sync::Mutex;
use tokio::time::{Instant, sleep, timeout_at};

use crate::domain_fronter::AppsScriptDomainFronter;
use crate::mitm::MitmCertManager;
use crate::proxy::{HANDSHAKE_TIMEOUT, http_relay, mitm_rewrite};
use crate::telemetry::SharedTelemetryState;

/// Pause before peeking again at a partial HTTP method token.
const PEEK_RETRY_INTERVAL: Duration = Duration::from_millis(10);

/// # Cancel safety:
///
/// NOT cancel-safe: the initial peek does not consume, but dispatch then delegates
/// to the MITM / HTTP-relay handlers which read and partial-write across awaits.
/// Cancellation can truncate the dispatched connection.
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
        return mitm_rewrite::sni_rewrite_tunnel(stream, host, port, upstream, relay, mitm, telemetry).await;
    }

    let deadline = Instant::now() + HANDSHAKE_TIMEOUT;
    let mut peek = [0u8; 8];
    let peeked = loop {
        match timeout_at(deadline, stream.peek(&mut peek)).await {
            Ok(Ok(bytes)) if bytes > 0 && http_relay::is_http_method_prefix(&peek[..bytes]) => {
                // "GET" without its space yet: wait for the full method token.
                if Instant::now() + PEEK_RETRY_INTERVAL >= deadline {
                    break bytes;
                }
                sleep(PEEK_RETRY_INTERVAL).await;
            }
            Ok(Ok(bytes)) => break bytes,
            Ok(Err(error)) => return Err(error),
            Err(_) => return Err(io::Error::new(io::ErrorKind::TimedOut, "client protocol detection timed out")),
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

    telemetry.record_error("non-HTTP traffic cannot use the Apps Script relay; refused".to_string());
    Err(io::Error::new(io::ErrorKind::Unsupported, "Apps Script supports HTTP traffic only"))
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

#[cfg(test)]
mod tests {
    use tokio::io::AsyncWriteExt;
    use tokio::net::TcpListener;
    use tokio::time::timeout;
    use tokio_rustls::TlsConnector;
    use tokio_rustls::rustls::pki_types::pem::PemObject;
    use tokio_rustls::rustls::pki_types::{CertificateDer, ServerName};
    use tokio_rustls::rustls::{ClientConfig, RootCertStore};

    use super::*;
    use crate::AppsScriptRuntimeConfig;
    use crate::telemetry::RelayTelemetryState;

    struct Fixture {
        dir: std::path::PathBuf,
        relay: Arc<AppsScriptDomainFronter>,
        mitm: Arc<Mutex<MitmCertManager>>,
        telemetry: SharedTelemetryState,
    }

    impl Fixture {
        fn new(name: &str) -> Self {
            let dir = std::env::temp_dir().join(format!("ripdpi-dispatch-{name}-{}", std::process::id()));
            let _ = std::fs::remove_dir_all(&dir);
            let config = AppsScriptRuntimeConfig::from_json(
                r#"{"scriptIds":["script-id"],"authKey":"secret","googleIp":"127.0.0.1"}"#,
            )
            .expect("config");
            let telemetry = Arc::new(RelayTelemetryState::new(&config));
            Self {
                mitm: Arc::new(Mutex::new(MitmCertManager::new_in(&dir).expect("MITM CA"))),
                relay: Arc::new(AppsScriptDomainFronter::new(&config, telemetry.clone())),
                telemetry,
                dir,
            }
        }

        /// Runs `dispatch` for the server side of a fresh loopback connection.
        async fn dispatch(&self, host: &str, port: u16) -> (TcpStream, tokio::task::JoinHandle<io::Result<()>>) {
            let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind");
            let client = TcpStream::connect(listener.local_addr().expect("address")).await.expect("connect");
            let (server, _) = listener.accept().await.expect("accept");
            let (host, relay, mitm, telemetry) =
                (host.to_string(), self.relay.clone(), self.mitm.clone(), self.telemetry.clone());
            let task =
                tokio::spawn(
                    async move { dispatch(server, &host, port, relay, mitm, telemetry, HashMap::new()).await },
                );
            (client, task)
        }
    }

    impl Drop for Fixture {
        fn drop(&mut self) {
            let _ = std::fs::remove_dir_all(&self.dir);
        }
    }

    #[tokio::test(flavor = "current_thread", start_paused = true)]
    async fn silent_client_to_a_named_host_is_not_resolved_and_dialled_directly() {
        let fixture = Fixture::new("named-passthrough");
        let origin = TcpListener::bind("127.0.0.1:0").await.expect("bind origin");
        let origin_port = origin.local_addr().expect("origin address").port();

        let (_client, task) = fixture.dispatch("localhost", origin_port).await;
        // The paused clock skips the full first-bytes wait of a named target.
        let _ = timeout(HANDSHAKE_TIMEOUT + Duration::from_secs(5), task).await.expect("dispatch ends");
        assert!(
            timeout(Duration::from_millis(300), origin.accept()).await.is_err(),
            "a SOCKS5 domain target must not be resolved locally and dialled directly"
        );
    }

    #[tokio::test]
    async fn slow_tls_client_on_port_443_still_reaches_the_mitm() {
        let fixture = Fixture::new("slow-tls");
        let ca = CertificateDer::from_pem_file(fixture.dir.join("relay-ca/ca.crt")).expect("CA certificate");
        let mut roots = RootCertStore::empty();
        roots.add(ca).expect("trust relay CA");
        let connector =
            TlsConnector::from(Arc::new(ClientConfig::builder().with_root_certificates(roots).with_no_client_auth()));

        let (client, _task) = fixture.dispatch("slow.example", 443).await;
        // Slower than the 300 ms first-bytes guess for server-first protocols.
        tokio::time::sleep(Duration::from_millis(500)).await;
        let name = ServerName::try_from("slow.example").expect("name");
        let mut tls = timeout(Duration::from_secs(5), connector.connect(name, client))
            .await
            .expect("handshake ends")
            .expect("a slow TLS client must be answered by the MITM, not sent direct");
        let _ = tls.shutdown().await;
    }

    #[tokio::test]
    async fn slow_tls_client_to_a_named_host_on_another_port_still_reaches_the_mitm() {
        let fixture = Fixture::new("slow-tls-993");
        let ca = CertificateDer::from_pem_file(fixture.dir.join("relay-ca/ca.crt")).expect("CA certificate");
        let mut roots = RootCertStore::empty();
        roots.add(ca).expect("trust relay CA");
        let connector =
            TlsConnector::from(Arc::new(ClientConfig::builder().with_root_certificates(roots).with_no_client_auth()));

        let (client, _task) = fixture.dispatch("imap.example", 993).await;
        // Slower than the 300 ms first-bytes guess for server-first protocols.
        tokio::time::sleep(Duration::from_millis(500)).await;
        let name = ServerName::try_from("imap.example").expect("name");
        let mut tls = timeout(Duration::from_secs(5), connector.connect(name, client))
            .await
            .expect("handshake ends")
            .expect("a slow TLS client to a named host must be answered by the MITM, not refused");
        let _ = tls.shutdown().await;
    }

    #[tokio::test]
    async fn http_method_split_across_writes_is_relayed_not_passed_through() {
        let fixture = Fixture::new("split-method");
        let origin = TcpListener::bind("127.0.0.1:0").await.expect("bind origin");
        let origin_port = origin.local_addr().expect("origin address").port();

        let (mut client, _task) = fixture.dispatch("127.0.0.1", origin_port).await;
        client.write_all(b"GET").await.expect("first part");
        tokio::time::sleep(Duration::from_millis(100)).await;
        client.write_all(b" / HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n").await.expect("rest of the request");
        assert!(
            timeout(Duration::from_millis(500), origin.accept()).await.is_err(),
            "an HTTP request must go to the relay, not straight to the origin"
        );
    }
    /// # Cancel safety:
    /// NOT cancel-safe: cancellation detaches the fixture task; the test runtime cleans it up.
    #[tokio::test(flavor = "current_thread")]
    async fn rly15_non_http_ip_never_dials_the_origin() {
        let fixture = Fixture::new("no-ip-bypass");
        let origin = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let (mut client, task) = fixture.dispatch("127.0.0.1", origin.local_addr().unwrap().port()).await;
        client.write_all(b"SSH-2.0-marker\r\n").await.unwrap();
        let result = task.await.unwrap().unwrap_err();
        assert_eq!(result.kind(), io::ErrorKind::Unsupported);
        assert!(timeout(Duration::from_millis(200), origin.accept()).await.is_err());
    }

    /// # Cancel safety:
    /// NOT cancel-safe: cancellation detaches the fixture task; the test runtime cleans it up.
    #[tokio::test(flavor = "current_thread", start_paused = true)]
    async fn rly15_silent_ip_never_dials_the_origin() {
        let fixture = Fixture::new("no-silent-ip-bypass");
        let origin = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let (_client, task) = fixture.dispatch("127.0.0.1", origin.local_addr().unwrap().port()).await;
        assert_eq!(task.await.unwrap().unwrap_err().kind(), io::ErrorKind::TimedOut);
        assert!(timeout(Duration::from_millis(200), origin.accept()).await.is_err());
    }
}
