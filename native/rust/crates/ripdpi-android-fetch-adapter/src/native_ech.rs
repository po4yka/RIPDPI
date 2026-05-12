use std::io;
use std::sync::Arc;
use std::time::{Duration, Instant};

use base64::Engine;
use rustls::client::{EchConfig, EchMode, EchStatus};
use rustls::pki_types::{EchConfigListBytes, ServerName};
use rustls::{ClientConfig, RootCertStore};
use serde::{Deserialize, Serialize};
use tokio::time::timeout;
use tokio_rustls::TlsConnector;

use crate::socket_protection::connect_transport;

pub(crate) fn connect(request_json: &str) -> io::Result<String> {
    let request: NativeEchTlsHandshakeRequest =
        serde_json::from_str(request_json).map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().map_err(io::Error::other)?;
    let payload = runtime.block_on(connect_async(request)).unwrap_or_else(NativeEchTlsHandshakeResponse::error);
    serde_json::to_string(&payload).map_err(io::Error::other)
}

async fn connect_async(request: NativeEchTlsHandshakeRequest) -> io::Result<NativeEchTlsHandshakeResponse> {
    let config = build_ech_client_config(&request.ech_config_bytes_b64)?;
    let connector = TlsConnector::from(Arc::new(config));
    let server_name = ServerName::try_from(request.target.clone())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid ECH target: {error}")))?;
    let tcp = connect_transport(&request.target, HTTPS_PORT, request.connect_timeout_ms).await?;
    let started = Instant::now();
    let stream = timeout(Duration::from_millis(request.connect_timeout_ms), connector.connect(server_name, tcp))
        .await
        .map_err(|_| {
            io::Error::new(io::ErrorKind::TimedOut, format!("ECH TLS handshake to {} timed out", request.target))
        })?
        .map_err(|error| {
            io::Error::new(io::ErrorKind::ConnectionRefused, format!("ECH TLS handshake failed: {error}"))
        })?;
    let negotiated_ech = matches!(stream.get_ref().1.ech_status(), EchStatus::Accepted);
    Ok(NativeEchTlsHandshakeResponse {
        negotiated_ech: Some(negotiated_ech),
        latency_ms: Some(started.elapsed().as_millis() as u64),
        error: None,
    })
}

fn build_ech_client_config(ech_config_bytes_b64: &str) -> io::Result<ClientConfig> {
    let ech_config_bytes = base64::engine::general_purpose::STANDARD
        .decode(ech_config_bytes_b64.trim())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid ECH config base64: {error}")))?;
    let ech_config = EchConfig::new(
        EchConfigListBytes::from(ech_config_bytes),
        rustls::crypto::aws_lc_rs::hpke::ALL_SUPPORTED_SUITES,
    )
    .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid ECH config: {error}")))?;
    let builder = ClientConfig::builder_with_provider(rustls::crypto::aws_lc_rs::default_provider().into())
        .with_ech(EchMode::Enable(ech_config))
        .map_err(|error| io::Error::other(format!("enable ECH: {error}")))?;
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let mut config = builder.with_root_certificates(roots).with_no_client_auth();
    config.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];
    Ok(config)
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct NativeEchTlsHandshakeRequest {
    target: String,
    ech_config_bytes_b64: String,
    connect_timeout_ms: u64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct NativeEchTlsHandshakeResponse {
    negotiated_ech: Option<bool>,
    latency_ms: Option<u64>,
    error: Option<String>,
}

impl NativeEchTlsHandshakeResponse {
    fn error(error: io::Error) -> Self {
        Self { negotiated_ech: None, latency_ms: None, error: Some(error.to_string()) }
    }
}

const HTTPS_PORT: u16 = 443;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn malformed_ech_config_serializes_error_payload() {
        let payload = connect(r#"{"target":"example.com","echConfigBytesB64":"%%%","connectTimeoutMs":1}"#)
            .expect("response should serialize");

        assert!(payload.contains("invalid ECH config base64"));
    }
}
