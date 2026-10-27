use std::time::Duration;

use tokio::net::TcpStream;
use tokio::time::timeout;
use tokio_rustls::TlsConnector;

use crate::AppsScriptRuntimeConfig;
use crate::telemetry::SharedTelemetryState;
use scripts::{AttemptError, ScriptPool, is_deployment_error};

mod headers;
mod http;
mod redirect;
mod relay_json;
mod request;
mod response;
mod scripts;
mod tls;

const HTTP_HOST: &str = "script.google.com";
const RELAY_TIMEOUT: Duration = Duration::from_secs(25);

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
    front_domain: String,
    auth_key: String,
    scripts: ScriptPool,
    tls_connector: TlsConnector,
    telemetry: SharedTelemetryState,
}

impl AppsScriptDomainFronter {
    pub fn new(config: &AppsScriptRuntimeConfig, telemetry: SharedTelemetryState) -> Self {
        Self {
            connect_host: config.google_ip.clone(),
            front_domain: config.front_domain.clone(),
            auth_key: config.auth_key.clone(),
            scripts: ScriptPool::new(config.script_ids.clone()),
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
    /// deployment cools down; a request that reached Apps Script moves on only when
    /// repeating it is harmless (see [`AttemptError::may_retry`]).
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
        for index in self.scripts.attempt_order() {
            match self.relay_once(self.scripts.id(index), &payload).await {
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
    async fn relay_once(&self, script_id: &str, payload: &[u8]) -> Result<Vec<u8>, AttemptError> {
        let sent = |error| AttemptError { error, sent: true, deployment_fault: true };
        let mut stream = self.open_fronted_stream().await.map_err(|error| AttemptError {
            error,
            sent: false,
            deployment_fault: true,
        })?;
        let path = format!("/macros/s/{script_id}/exec");

        // A request that failed while being written may still have reached Apps Script.
        http::send_post(&mut stream, &path, HTTP_HOST, payload).await.map_err(sent)?;
        let response = response::read(&mut stream).await.map_err(sent)?;
        let response = redirect::follow(&mut stream, response, HTTP_HOST).await.map_err(sent)?;

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

    /// # Cancel safety
    ///
    /// NOT cancel-safe: establishes a TCP + TLS connection through a multi-step
    /// handshake; cancellation mid-handshake leaves a partially negotiated TLS
    /// session that is dropped without a clean close_notify.
    async fn open_fronted_stream(&self) -> Result<tokio_rustls::client::TlsStream<TcpStream>, FronterError> {
        tls::connect_fronted_stream(&self.tls_connector, &self.connect_host, 443, &self.front_domain).await
    }

    /// Open a verified TLS stream to an SNI-rewrite `upstream`, presenting the
    /// front domain as SNI.
    ///
    /// # Cancel safety
    ///
    /// NOT cancel-safe: same multi-step TCP + TLS handshake as
    /// `open_fronted_stream`.
    pub(crate) async fn open_rewrite_stream(
        &self,
        upstream: &str,
        port: u16,
    ) -> Result<tokio_rustls::client::TlsStream<TcpStream>, FronterError> {
        timeout(RELAY_TIMEOUT, tls::connect_fronted_stream(&self.tls_connector, upstream, port, &self.front_domain))
            .await
            .map_err(|_| FronterError::Timeout)?
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
    async fn a_request_that_cannot_reach_one_deployment_is_tried_on_the_next() {
        let mut config =
            AppsScriptRuntimeConfig::from_json(r#"{"scriptIds":["script-a","script-b"],"authKey":"secret"}"#)
                .expect("config");
        // Nothing listens on loopback port 443: every attempt fails before the request is sent.
        config.google_ip = "127.0.0.1".to_string();
        let fronter = AppsScriptDomainFronter::new(&config, Arc::new(RelayTelemetryState::new(&config)));

        let response = fronter.relay("POST", "https://target.example/form", &[], b"data").await;

        assert!(response.starts_with(b"HTTP/1.1 502 "));
        assert!(fronter.scripts.is_cooling_now(0) && fronter.scripts.is_cooling_now(1), "both deployments were tried");
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
