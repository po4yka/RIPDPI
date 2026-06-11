use std::sync::atomic::{AtomicUsize, Ordering};
use std::time::Duration;

use tokio::net::TcpStream;
use tokio::time::timeout;
use tokio_rustls::TlsConnector;

use crate::AppsScriptRuntimeConfig;

mod headers;
mod http;
mod redirect;
mod relay_json;
mod request;
mod response;
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

pub struct AppsScriptDomainFronter {
    connect_host: String,
    front_domain: String,
    auth_key: String,
    script_ids: Vec<String>,
    next_script_index: AtomicUsize,
    tls_connector: TlsConnector,
}

impl AppsScriptDomainFronter {
    pub fn new(config: &AppsScriptRuntimeConfig) -> Self {
        Self {
            connect_host: config.google_ip.clone(),
            front_domain: config.front_domain.clone(),
            auth_key: config.auth_key.clone(),
            script_ids: config.script_ids.clone(),
            next_script_index: AtomicUsize::new(0),
            tls_connector: tls::connector(),
        }
    }

    // NOT cancel-safe: drives relay_once (which opens a fronted stream and
    // partial-writes the upstream request) under a timeout; cancelling the
    // outer future abandons the in-flight upstream request mid-exchange.
    pub async fn relay(&self, method: &str, url: &str, headers: &[(String, String)], body: &[u8]) -> Vec<u8> {
        match timeout(RELAY_TIMEOUT, self.relay_once(method, url, headers, body)).await {
            Ok(Ok(response)) => response,
            Ok(Err(error)) => error_response(502, &format!("Apps Script relay error: {error}")),
            Err(_) => error_response(504, "Apps Script relay timeout"),
        }
    }

    // NOT cancel-safe: send_post writes the request and then response::read /
    // redirect::follow consume the reply across many awaits; cancellation can
    // truncate the request or drop already-consumed response bytes.
    async fn relay_once(
        &self,
        method: &str,
        url: &str,
        headers: &[(String, String)],
        body: &[u8],
    ) -> Result<Vec<u8>, FronterError> {
        let mut stream = self.open_fronted_stream().await?;
        let payload = request::build_payload_json(&self.auth_key, method, url, headers, body)?;
        let path = format!("/macros/s/{}/exec", self.next_script_id());

        http::send_post(&mut stream, &path, HTTP_HOST, &payload).await?;
        let response = response::read(&mut stream).await?;
        let response = redirect::follow(&mut stream, response, HTTP_HOST).await?;

        if response.status != 200 {
            let snippet = String::from_utf8_lossy(&response.body);
            return Err(FronterError::Relay(format!(
                "Apps Script HTTP {}: {}",
                response.status,
                snippet.chars().take(200).collect::<String>()
            )));
        }

        relay_json::parse(&response.body)
    }

    // NOT cancel-safe: establishes a TCP + TLS connection through a multi-step
    // handshake; cancellation mid-handshake leaves a partially negotiated TLS
    // session that is dropped without a clean close_notify.
    async fn open_fronted_stream(&self) -> Result<tokio_rustls::client::TlsStream<TcpStream>, FronterError> {
        tls::connect_fronted_stream(&self.tls_connector, &self.connect_host, &self.front_domain).await
    }

    fn next_script_id(&self) -> String {
        // Ordering: the counter only distributes requests across script IDs.
        // No state is published through the selected index.
        let index = self.next_script_index.fetch_add(1, Ordering::Relaxed);
        self.script_ids[index % self.script_ids.len()].clone()
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
