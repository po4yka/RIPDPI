use std::time::Duration;

use tokio::net::TcpStream;
use tokio::time::{Instant, timeout, timeout_at};
use tokio_rustls::TlsConnector;

use crate::AppsScriptRuntimeConfig;
use crate::telemetry::SharedTelemetryState;
use scripts::{AttemptError, DEPLOYMENT_COOLDOWN, ScriptPool, is_deployment_error};

mod headers;
mod http;
mod redirect;
mod relay_json;
mod request;
mod response;
mod scripts;
mod tls;

#[cfg(test)]
mod failover_tests;

const HTTP_HOST: &str = "script.google.com";
const RELAY_TIMEOUT: Duration = Duration::from_secs(25);
/// The shortest bound on one deployment attempt (the bound is otherwise an even share of
/// [`RELAY_TIMEOUT`]), so a slow but working deployment still has time to answer.
const MIN_ATTEMPT_TIMEOUT: Duration = Duration::from_secs(8);
/// How long a front SNI name whose connection failed is used only after the others.
const SNI_COOLDOWN: Duration = Duration::from_secs(30);

#[derive(Debug, thiserror::Error)]
pub enum FronterError {
    #[error("io: {0}")]
    Io(#[from] std::io::Error),
    #[error("tls: {0}")]
    Tls(#[from] rustls::Error),
    #[error("json: {0}")]
    Json(#[from] serde_json::Error),
    #[error("invalid dns name: {0}")]
    InvalidDnsName(#[from] rustls::pki_types::InvalidDnsNameError),
    #[error("bad response: {0}")]
    BadResponse(String),
    #[error("relay error: {0}")]
    Relay(String),
    #[error("timeout")]
    Timeout,
}

impl FronterError {
    /// A short failure class for telemetry, without hosts, URLs or response text.
    fn class(&self) -> String {
        match self {
            Self::Io(error) => format!("io {:?}", error.kind()),
            Self::Tls(_) => "tls".to_string(),
            Self::Json(_) => "invalid relay json".to_string(),
            Self::InvalidDnsName(_) => "invalid front domain".to_string(),
            Self::BadResponse(_) => "bad response".to_string(),
            Self::Relay(_) => "relay error".to_string(),
            Self::Timeout => "timeout".to_string(),
        }
    }
}

pub struct AppsScriptDomainFronter {
    connect_host: String,
    /// The front's TLS port: 443, a loopback port in tests.
    front_port: u16,
    /// TLS SNI names, used in turn per connection (`sniHosts`, or the front domain alone);
    /// a name whose front connection failed cools down.
    sni_hosts: ScriptPool,
    auth_key: String,
    scripts: ScriptPool,
    tls_connector: TlsConnector,
    telemetry: SharedTelemetryState,
}

impl AppsScriptDomainFronter {
    pub fn new(config: &AppsScriptRuntimeConfig, telemetry: SharedTelemetryState) -> Self {
        Self {
            connect_host: config.google_ip.clone(),
            front_port: 443,
            sni_hosts: ScriptPool::new(
                if config.sni_hosts.is_empty() { vec![config.front_domain.clone()] } else { config.sni_hosts.clone() },
                SNI_COOLDOWN,
            ),
            auth_key: config.auth_key.clone(),
            scripts: ScriptPool::new(config.script_ids.clone(), DEPLOYMENT_COOLDOWN),
            tls_connector: tls::connector(),
            telemetry,
        }
    }

    /// # Cancel safety
    ///
    /// NOT cancel-safe: drives relay_once (which opens a fronted stream and
    /// partial-writes the upstream request) under a timeout; cancelling the
    /// outer future abandons the in-flight upstream request mid-exchange.
    pub async fn relay(&self, method: &str, url: &str, headers: &[(String, String)], body: &[u8]) -> Vec<u8> {
        match timeout(RELAY_TIMEOUT, self.relay_with_failover(method, url, headers, body)).await {
            Ok(Ok(response)) => {
                self.telemetry.record_success(response.len());
                response
            }
            Ok(Err(error)) => {
                // The class only: the error text can quote the Apps Script page or the target.
                self.telemetry.record_error(format!("Apps Script relay failed: {}", error.class()));
                error_response(502, &format!("Apps Script relay error: {error}"))
            }
            Err(_) => {
                self.telemetry.record_error("Apps Script relay failed: timeout".to_string());
                error_response(504, "Apps Script relay timeout")
            }
        }
    }

    /// Send the request to one deployment after another until one answers. A failing
    /// deployment cools down; a request that reached Apps Script moves on only after a
    /// deployment fault and when repeating it is harmless (see [`AttemptError::may_retry`]).
    ///
    /// # Cancel safety
    ///
    /// NOT cancel-safe: each attempt is a multi-await exchange (see `relay_once`).
    async fn relay_with_failover(
        &self,
        method: &str,
        url: &str,
        headers: &[(String, String)],
        body: &[u8],
    ) -> Result<Vec<u8>, FronterError> {
        let payload = request::build_payload_json(&self.auth_key, method, url, headers, body)?;
        let mut last_error = None;
        let order = self.scripts.attempt_order();
        // A stalled deployment is cut off within the total budget, so the next one still has time.
        let attempt_limit =
            (RELAY_TIMEOUT / u32::try_from(order.len()).unwrap_or(u32::MAX).max(1)).max(MIN_ATTEMPT_TIMEOUT);
        for index in order {
            match self.relay_once(self.scripts.id(index), &payload, attempt_limit).await {
                Ok(response) => {
                    self.scripts.record(index, true);
                    return Ok(response);
                }
                Err(attempt) => {
                    if attempt.deployment_fault {
                        self.scripts.record(index, false);
                    }
                    let retry = attempt.may_retry(method);
                    last_error = Some(attempt.error);
                    if !retry {
                        break;
                    }
                }
            }
        }
        Err(last_error.unwrap_or_else(|| FronterError::Relay("no Apps Script deployment configured".to_string())))
    }

    /// # Cancel safety
    ///
    /// NOT cancel-safe: send_post writes the request and then response::read /
    /// redirect::follow consume the reply across many awaits; cancellation can
    /// truncate the request or drop already-consumed response bytes.
    async fn relay_once(&self, script_id: &str, payload: &[u8], limit: Duration) -> Result<Vec<u8>, AttemptError> {
        let deadline = Instant::now() + limit;
        let sent = |error| AttemptError { error, sent: true, deployment_fault: true };
        // A front that cannot be opened is a front or SNI fault, not one of the deployment.
        let mut stream = self
            .connect_front(&self.connect_host, self.front_port, limit)
            .await
            .map_err(|error| AttemptError { error, sent: false, deployment_fault: false })?;
        let path = format!("/macros/s/{script_id}/exec");

        // A request that failed while being written may still have reached Apps Script.
        let exchange = async {
            http::send_post(&mut stream, &path, HTTP_HOST, payload).await?;
            let response = response::read(&mut stream).await?;
            redirect::follow(&mut stream, response, HTTP_HOST).await
        };
        // A deployment that does not answer by the deadline is cut off and cools like a failed one.
        let response = timeout_at(deadline, exchange)
            .await
            .map_err(|_| FronterError::Timeout)
            .and_then(|response| response)
            .map_err(sent)?;

        if response.status != 200 {
            let snippet = String::from_utf8_lossy(&response.body);
            return Err(sent(FronterError::Relay(format!(
                "Apps Script HTTP {}: {}",
                response.status,
                snippet.chars().take(200).collect::<String>()
            ))));
        }

        relay_json::parse(&response.body).map_err(|error| {
            // An error the script reports is about the target unless it names a deployment limit.
            let deployment_fault = !matches!(&error, FronterError::Relay(message) if !is_deployment_error(message));
            AttemptError { error, sent: true, deployment_fault }
        })
    }

    /// Open a verified TLS stream to `host:port` with the next SNI name (round-robin, a
    /// name that failed recently last), within `limit`, and record the name's health.
    ///
    /// # Cancel safety
    ///
    /// NOT cancel-safe: establishes a TCP + TLS connection through a multi-step
    /// handshake; cancellation mid-handshake leaves a partially negotiated TLS
    /// session that is dropped without a clean close_notify, and records no health.
    async fn connect_front(
        &self,
        host: &str,
        port: u16,
        limit: Duration,
    ) -> Result<tokio_rustls::client::TlsStream<TcpStream>, FronterError> {
        let Some(sni) = self.sni_hosts.pick() else {
            return Err(FronterError::Relay("no front SNI name configured".to_string()));
        };
        let connect = tls::connect_fronted_stream(&self.tls_connector, host, port, self.sni_hosts.id(sni));
        let result = timeout(limit, connect).await.map_err(|_| FronterError::Timeout).and_then(|result| result);
        self.sni_hosts.record(sni, result.is_ok());
        result
    }

    /// Open a verified TLS stream to an SNI-rewrite `upstream`, presenting the
    /// next SNI name (see [`Self::connect_front`]).
    ///
    /// # Cancel safety
    ///
    /// NOT cancel-safe: same multi-step TCP + TLS handshake as
    /// `connect_front`.
    pub(crate) async fn open_rewrite_stream(
        &self,
        upstream: &str,
        port: u16,
    ) -> Result<tokio_rustls::client::TlsStream<TcpStream>, FronterError> {
        self.connect_front(upstream, port, RELAY_TIMEOUT).await
    }
}

pub(crate) fn error_response(status: u16, message: &str) -> Vec<u8> {
    let body = message.as_bytes();
    let mut output = Vec::with_capacity(body.len() + 128);
    output.extend_from_slice(format!("HTTP/1.1 {status} {}\r\n", relay_json::status_text(status)).as_bytes());
    output.extend_from_slice(
        format!(
            "Content-Length: {}\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\n\r\n",
            body.len()
        )
        .as_bytes(),
    );
    output.extend_from_slice(body);
    output
}

#[cfg(test)]
mod tests {
    use std::sync::Arc;

    use super::*;
    use crate::telemetry::RelayTelemetryState;

    #[tokio::test(flavor = "current_thread")]
    async fn front_connections_rotate_over_the_configured_sni_hosts() {
        let config = AppsScriptRuntimeConfig::from_json(
            r#"{"scriptIds":["script-id"],"authKey":"secret","sniHosts":["mail.google.com","drive.google.com"]}"#,
        )
        .expect("config");
        let fronter = AppsScriptDomainFronter::new(&config, Arc::new(RelayTelemetryState::new(&config)));
        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.expect("bind");
        let port = listener.local_addr().expect("address").port();

        let mut names = Vec::new();
        for _ in 0..3 {
            let read_hello = async {
                let (mut server, _) = listener.accept().await.expect("accept");
                let mut hello = vec![0u8; 4096];
                let size = tokio::io::AsyncReadExt::read(&mut server, &mut hello).await.expect("ClientHello");
                // Dropping the server ends the handshake, so the connect returns at once.
                String::from_utf8_lossy(&hello[..size]).into_owned()
            };
            let (_connect, hello) = tokio::join!(fronter.open_rewrite_stream("127.0.0.1", port), read_hello);
            names.push(["mail.google.com", "drive.google.com"].into_iter().find(|name| hello.contains(name)));
        }

        assert_eq!(names, vec![Some("mail.google.com"), Some("drive.google.com"), Some("mail.google.com")]);
    }

    #[tokio::test(flavor = "current_thread")]
    async fn a_failed_relay_marks_the_relay_degraded_without_leaking_the_target() {
        let mut config =
            AppsScriptRuntimeConfig::from_json(r#"{"scriptIds":["script-id"],"authKey":"secret"}"#).expect("config");
        // Nothing listens on loopback port 443, so the front connection is refused.
        config.google_ip = "127.0.0.1".to_string();
        let telemetry = Arc::new(RelayTelemetryState::new(&config));
        telemetry.mark_listener_bound("127.0.0.1:11980".to_string());
        let fronter = AppsScriptDomainFronter::new(&config, telemetry.clone());

        let response = fronter.relay("GET", "https://private.example/path", &[], &[]).await;

        assert!(response.starts_with(b"HTTP/1.1 502 "));
        let snapshot = telemetry.snapshot(&config);
        assert_eq!((snapshot.total_errors, snapshot.health.as_str()), (1, "degraded"));
        let last_error = snapshot.last_error.expect("last error");
        assert!(last_error.starts_with("Apps Script relay failed: "), "{last_error}");
        assert!(!last_error.contains("private.example"), "{last_error}");
    }
}
