use std::io;
use std::time::Duration;

use base64::Engine;
use rustls::client::{EchConfig, EchMode};
use rustls::pki_types::EchConfigListBytes;
use rustls::{ClientConfig, RootCertStore};

use crate::native_ech_retry::EchEndpoint;
use serde::{Deserialize, Serialize};
use tokio::sync::watch;

pub(crate) fn connect(request_json: &str) -> io::Result<String> {
    let (_sender, receiver) = watch::channel(false);
    connect_cancellable(request_json, receiver)
}

pub(crate) fn connect_cancellable(request_json: &str, cancelled: watch::Receiver<bool>) -> io::Result<String> {
    let request: NativeEchTlsHandshakeRequest =
        serde_json::from_str(request_json).map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().map_err(io::Error::other)?;
    let payload = runtime
        .block_on(crate::native_ech_cancellation::run(cancelled, connect_async(request)))
        .unwrap_or_else(NativeEchTlsHandshakeResponse::error);
    serde_json::to_string(&payload).map_err(io::Error::other)
}

/// # Cancel safety:
/// cancel-safe: dropping this future closes its DNS exchange, unconnected socket or TLS stream.
async fn connect_async(request: NativeEchTlsHandshakeRequest) -> io::Result<NativeEchTlsHandshakeResponse> {
    if request.connect_port == 0 {
        return Err(io::Error::new(io::ErrorKind::PermissionDenied, "invalid ECH binding port"));
    }
    let configs = base64::engine::general_purpose::STANDARD
        .decode(request.ech_config_bytes_b64.trim())
        .map_err(|error| io::Error::new(io::ErrorKind::Unsupported, format!("invalid ECH config base64: {error}")))?;
    let latency_ms = crate::native_ech_retry::connect(
        EchEndpoint {
            server_name: &request.origin,
            host: &request.connect_host,
            port: request.connect_port,
            require_public_address: !request.origin.eq_ignore_ascii_case(&request.connect_host)
                || request.connect_port != HTTPS_PORT,
        },
        configs,
        Duration::from_millis(request.connect_timeout_ms),
        build_ech_client_config_from_list,
    )
    .await?;
    Ok(NativeEchTlsHandshakeResponse {
        negotiated_ech: Some(true),
        latency_ms: Some(latency_ms),
        error: None,
        cancelled: false,
        transport_setup: None,
    })
}

fn build_ech_client_config_from_list(ech_config_bytes: Vec<u8>) -> io::Result<ClientConfig> {
    let ech_config = EchConfig::new(
        EchConfigListBytes::from(ech_config_bytes),
        rustls::crypto::aws_lc_rs::hpke::ALL_SUPPORTED_SUITES,
    )
    .map_err(|error| io::Error::new(io::ErrorKind::Unsupported, format!("invalid ECH config: {error}")))?;
    let builder = ClientConfig::builder_with_provider(rustls::crypto::aws_lc_rs::default_provider().into())
        .with_ech(EchMode::Enable(ech_config))
        .map_err(|error| io::Error::new(io::ErrorKind::Unsupported, format!("enable ECH: {error}")))?;
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let mut config = ripdpi_tls_profiles::cert_blocklist::with_blocklisted_roots(builder, roots)
        .map_err(|error| io::Error::new(io::ErrorKind::NotConnected, format!("build certificate verifier: {error}")))?
        .with_no_client_auth();
    config.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];
    Ok(config)
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct NativeEchTlsHandshakeRequest {
    origin: String,
    connect_host: String,
    connect_port: u16,
    ech_config_bytes_b64: String,
    connect_timeout_ms: u64,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct NativeEchTlsHandshakeResponse {
    negotiated_ech: Option<bool>,
    latency_ms: Option<u64>,
    error: Option<String>,
    #[serde(skip_serializing_if = "std::ops::Not::not")]
    cancelled: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    transport_setup: Option<EchTransportSetupError>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
enum EchTransportSetupError {
    UnusableConfig,
    EndpointDenied,
    Unavailable,
}

impl NativeEchTlsHandshakeResponse {
    fn error(error: io::Error) -> Self {
        Self {
            negotiated_ech: None,
            latency_ms: None,
            cancelled: error.kind() == io::ErrorKind::Interrupted,
            transport_setup: match error.kind() {
                io::ErrorKind::Unsupported => Some(EchTransportSetupError::UnusableConfig),
                io::ErrorKind::PermissionDenied => Some(EchTransportSetupError::EndpointDenied),
                io::ErrorKind::NotConnected => Some(EchTransportSetupError::Unavailable),
                _ => None,
            },
            error: Some(error.to_string()),
        }
    }
}

const HTTPS_PORT: u16 = 443;

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn malformed_ech_config_serializes_error_payload() {
        let payload = connect(
            r#"{"origin":"example.com","connectHost":"edge.example","connectPort":8443,"echConfigBytesB64":"%%%","connectTimeoutMs":1}"#,
        )
        .expect("response should serialize");
        let response: serde_json::Value = serde_json::from_str(&payload).expect("response should be JSON");

        assert_eq!(response["transportSetup"], "unusableConfig");
        assert!(response["error"].as_str().is_some_and(|text| text.contains("invalid ECH config base64")));
    }
}
