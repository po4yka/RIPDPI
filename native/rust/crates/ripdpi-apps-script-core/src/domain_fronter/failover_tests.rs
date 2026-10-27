//! Failover tests against a loopback front that serves the Apps Script relay over TLS.

use std::sync::{Arc, Mutex};

use rustls::pki_types::{PrivateKeyDer, PrivatePkcs8KeyDer};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio_rustls::{TlsAcceptor, TlsConnector};

use super::AppsScriptDomainFronter;
use crate::AppsScriptRuntimeConfig;
use crate::telemetry::{RelayTelemetryState, SharedTelemetryState};

/// A relay answer with the body "ok".
const OK: &str = r#"{"s":200,"b":"b2s="}"#;
/// An error the script reports for the target fetch.
const TARGET_ERROR: &str = r#"{"e":"Exception: DNS error: https://target.example"}"#;
/// An error the script reports for its own quota.
const QUOTA_ERROR: &str = r#"{"e":"Exception: Service invoked too many times for one day: urlfetch."}"#;

/// What the front answers for one deployment.
#[derive(Clone, Copy)]
enum Reply {
    /// HTTP 200 with this relay JSON body.
    Json(&'static str),
}

/// A loopback front with a certificate valid for `names` only.
struct FakeFront {
    port: u16,
    connector: TlsConnector,
    /// Script ids of the requests the front received, in order.
    requests: Arc<Mutex<Vec<String>>>,
}

impl FakeFront {
    async fn start(names: &[&str], reply: fn(&str) -> Reply) -> Self {
        let provider = Arc::new(rustls::crypto::aws_lc_rs::default_provider());
        let certified = rcgen::generate_simple_self_signed(names.iter().map(ToString::to_string).collect::<Vec<_>>())
            .expect("front certificate");
        let cert = certified.cert.der().clone();
        let key = PrivateKeyDer::Pkcs8(PrivatePkcs8KeyDer::from(certified.signing_key.serialize_der()));
        let server = rustls::ServerConfig::builder_with_provider(provider.clone())
            .with_safe_default_protocol_versions()
            .expect("server versions")
            .with_no_client_auth()
            .with_single_cert(vec![cert.clone()], key)
            .expect("server config");
        let mut roots = rustls::RootCertStore::empty();
        roots.add(cert).expect("front root");
        let client = rustls::ClientConfig::builder_with_provider(provider)
            .with_safe_default_protocol_versions()
            .expect("client versions")
            .with_root_certificates(roots)
            .with_no_client_auth();

        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind front");
        let port = listener.local_addr().expect("front address").port();
        let acceptor = TlsAcceptor::from(Arc::new(server));
        let requests = Arc::new(Mutex::new(Vec::new()));
        let seen = requests.clone();
        tokio::spawn(async move {
            while let Ok((tcp, _)) = listener.accept().await {
                let (acceptor, seen) = (acceptor.clone(), seen.clone());
                tokio::spawn(async move {
                    let Ok(mut tls) = acceptor.accept(tcp).await else { return };
                    let Some(script) = read_script_id(&mut tls).await else { return };
                    seen.lock().expect("requests").push(script.clone());
                    match reply(&script) {
                        Reply::Json(body) => {
                            let response = format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\n\r\n{body}", body.len());
                            let _ = tls.write_all(response.as_bytes()).await;
                            let _ = tls.flush().await;
                            // Keep the connection open until the client is done with it.
                            let _ = tls.read(&mut [0u8; 1]).await;
                        }
                    }
                });
            }
        });

        Self { port, connector: TlsConnector::from(Arc::new(client)), requests }
    }

    /// A fronter for `config_json` that reaches this front, and its running telemetry.
    fn fronter(&self, config_json: &str) -> (AppsScriptDomainFronter, SharedTelemetryState, AppsScriptRuntimeConfig) {
        let mut config = AppsScriptRuntimeConfig::from_json(config_json).expect("config");
        config.google_ip = "127.0.0.1".to_string();
        let telemetry = Arc::new(RelayTelemetryState::new(&config));
        telemetry.mark_listener_bound("127.0.0.1:11980".to_string());
        let mut fronter = AppsScriptDomainFronter::new(&config, telemetry.clone());
        fronter.front_port = self.port;
        fronter.tls_connector = self.connector.clone();
        (fronter, telemetry, config)
    }

    fn requests(&self) -> Vec<String> {
        self.requests.lock().expect("requests").clone()
    }
}

/// The script id of one `POST /macros/s/{id}/exec` request, after its whole body is read.
async fn read_script_id<S: AsyncRead + Unpin>(stream: &mut S) -> Option<String> {
    let mut data = Vec::new();
    let mut chunk = [0u8; 4096];
    let header_end = loop {
        if let Some(end) = data.windows(4).position(|window| window == b"\r\n\r\n") {
            break end + 4;
        }
        let read = stream.read(&mut chunk).await.ok().filter(|&read| read > 0)?;
        data.extend_from_slice(&chunk[..read]);
    };
    let head = String::from_utf8_lossy(&data[..header_end]).into_owned();
    let length: usize = head
        .lines()
        .find_map(|line| {
            line.to_ascii_lowercase().strip_prefix("content-length:").map(|value| value.trim().to_string())
        })
        .and_then(|value| value.parse().ok())
        .unwrap_or(0);
    while data.len() < header_end + length {
        let read = stream.read(&mut chunk).await.ok().filter(|&read| read > 0)?;
        data.extend_from_slice(&chunk[..read]);
    }
    let path = head.split_whitespace().nth(1)?;
    path.strip_prefix("/macros/s/")?.strip_suffix("/exec").map(str::to_string)
}

#[tokio::test(flavor = "current_thread")]
async fn a_get_that_failed_at_the_target_is_not_repeated_on_other_deployments() {
    let front = FakeFront::start(&["www.google.com"], |_| Reply::Json(TARGET_ERROR)).await;
    let (fronter, _telemetry, _config) = front.fronter(r#"{"scriptIds":["script-a","script-b"],"authKey":"secret"}"#);

    let response = fronter.relay("GET", "https://target.example/", &[], &[]).await;

    assert!(response.starts_with(b"HTTP/1.1 502 "), "{}", String::from_utf8_lossy(&response));
    assert_eq!(front.requests().len(), 1, "one deployment fetched the failing target");
    assert!(!fronter.scripts.is_cooling_now(0) && !fronter.scripts.is_cooling_now(1), "no deployment failed");
}

#[tokio::test(flavor = "current_thread")]
async fn a_get_that_a_deployment_failed_moves_to_the_next_deployment() {
    let front = FakeFront::start(&["www.google.com"], |script| {
        Reply::Json(if script == "script-a" { QUOTA_ERROR } else { OK })
    })
    .await;
    let (fronter, _telemetry, _config) = front.fronter(r#"{"scriptIds":["script-a","script-b"],"authKey":"secret"}"#);

    let response = fronter.relay("GET", "https://target.example/", &[], &[]).await;

    assert!(response.starts_with(b"HTTP/1.1 200 "), "{}", String::from_utf8_lossy(&response));
    assert_eq!(front.requests(), vec!["script-a".to_string(), "script-b".to_string()]);
    assert!(fronter.scripts.is_cooling_now(0) && !fronter.scripts.is_cooling_now(1));
}
