use std::io;
use std::net::{IpAddr, UdpSocket};
use std::os::fd::AsRawFd;
use std::time::Duration;

use base64::engine::general_purpose::STANDARD;
use base64::Engine;
use jni::objects::JString;
use jni::sys::jstring;
use jni::{EnvUnowned, Outcome};
use ripdpi_dns_resolver::{
    EncryptedDnsConnectHooks, EncryptedDnsEndpoint, EncryptedDnsError, EncryptedDnsErrorKind, EncryptedDnsProtocol,
    EncryptedDnsResolver, EncryptedDnsTransport,
};
use ripdpi_native_protect::{has_protect_callback, protect_socket_via_callback};
use serde::{Deserialize, Serialize};
use tokio::time::timeout;

pub fn exchange_entry(mut env: EnvUnowned<'_>, request_json: JString<'_>) -> jstring {
    match env
        .with_env(move |env| -> jni::errors::Result<jstring> {
            let request_json: String = request_json.mutf8_chars(env)?.to_str().into_owned();
            let payload = exchange(&request_json);
            Ok(env.new_string(payload)?.into_raw())
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        _ => std::ptr::null_mut(),
    }
}

fn exchange(request_json: &str) -> String {
    let request = match serde_json::from_str::<NativeDoqExchangeRequest>(request_json) {
        Ok(request) => request,
        Err(error) => return response_error("quic", format!("invalid native DoQ request: {error}")),
    };
    let query = match STANDARD.decode(request.query_base64.trim()) {
        Ok(query) => query,
        Err(error) => return response_error("quic", format!("invalid DoQ query encoding: {error}")),
    };
    let timeout_ms = request.timeout_ms.max(1);
    let runtime = match tokio::runtime::Builder::new_current_thread().enable_all().build() {
        Ok(runtime) => runtime,
        Err(error) => return response_error("quic", format!("build native DoQ runtime: {error}")),
    };

    let result = runtime.block_on(async {
        timeout(Duration::from_millis(timeout_ms), exchange_async(request, query, Duration::from_millis(timeout_ms)))
            .await
    });
    match result {
        Ok(Ok(response)) => response_ok(response),
        Ok(Err(error)) => response_error(error.kind, error.message),
        Err(_) => response_error("timeout", "native DoQ exchange timed out"),
    }
}

async fn exchange_async(
    request: NativeDoqExchangeRequest,
    query: Vec<u8>,
    timeout_duration: Duration,
) -> Result<Vec<u8>, NativeDoqError> {
    let bootstrap_ips = bootstrap_ips(&request.endpoint, request.port).await?;
    let resolver = EncryptedDnsResolver::with_timeout_and_connect_hooks(
        EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Doq,
            resolver_id: Some(request.endpoint.clone()),
            host: request.endpoint,
            port: request.port,
            tls_server_name: Some(request.tls_server_name),
            bootstrap_ips,
            doh_url: None,
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
        },
        EncryptedDnsTransport::Direct,
        timeout_duration,
        doq_connect_hooks(),
    )
    .map_err(NativeDoqError::from)?;
    resolver.exchange(&query).await.map_err(NativeDoqError::from)
}

// Cancel safety: cancel-safe. `tokio::net::lookup_host` is implemented via
// `spawn_blocking`; dropping this future does not leave runtime state behind
// other than the in-flight blocking task, which finishes on its own and is
// observed by no one. No mutation of caller-visible state spans the `.await`.
async fn bootstrap_ips(endpoint: &str, port: u16) -> Result<Vec<IpAddr>, NativeDoqError> {
    if let Ok(ip) = endpoint.parse::<IpAddr>() {
        return Ok(vec![ip]);
    }
    // Async DNS via tokio's blocking pool — avoids blocking the current_thread
    // runtime worker (which is the only worker on this JNI-spawned runtime, so
    // a sync `to_socket_addrs` would defeat the outer `tokio::time::timeout`
    // because the timer cannot fire while the worker is parked in libc resolve).
    let addresses = tokio::net::lookup_host((endpoint, port))
        .await
        .map_err(|error| NativeDoqError::new("quic", format!("resolve DoQ bootstrap {endpoint}: {error}")))?;
    let ips: Vec<IpAddr> = addresses.map(|addr| addr.ip()).collect();
    if ips.is_empty() {
        return Err(NativeDoqError::new("quic", format!("resolve DoQ bootstrap {endpoint}: no addresses")));
    }
    Ok(ips)
}

fn doq_connect_hooks() -> EncryptedDnsConnectHooks {
    EncryptedDnsConnectHooks::new().with_direct_udp_binder(|bind_addr| {
        let socket = UdpSocket::bind(bind_addr)?;
        if has_protect_callback() {
            protect_socket_via_callback(socket.as_raw_fd())
                .map_err(|error| io::Error::new(error.kind(), format!("protect native DoQ socket: {error}")))?;
        }
        Ok(socket)
    })
}

fn response_ok(response: Vec<u8>) -> String {
    serde_json::to_string(&NativeDoqExchangeResponse {
        response_base64: Some(STANDARD.encode(response)),
        error_kind: None,
        error: None,
    })
    .unwrap_or_else(|error| response_error("quic", format!("serialize native DoQ response: {error}")))
}

fn response_error(error_kind: impl Into<String>, error: impl Into<String>) -> String {
    serde_json::to_string(&NativeDoqExchangeResponse {
        response_base64: None,
        error_kind: Some(error_kind.into()),
        error: Some(error.into()),
    })
    .unwrap_or_else(|_| "{\"errorKind\":\"quic\",\"error\":\"serialize native DoQ error\"}".to_string())
}

#[derive(Debug, Deserialize)]
struct NativeDoqExchangeRequest {
    endpoint: String,
    port: u16,
    #[serde(rename = "tlsServerName")]
    tls_server_name: String,
    #[serde(rename = "queryBase64")]
    query_base64: String,
    #[serde(rename = "timeoutMs")]
    timeout_ms: u64,
}

#[derive(Debug, Serialize)]
struct NativeDoqExchangeResponse {
    #[serde(rename = "responseBase64")]
    response_base64: Option<String>,
    #[serde(rename = "errorKind")]
    error_kind: Option<String>,
    error: Option<String>,
}

struct NativeDoqError {
    kind: &'static str,
    message: String,
}

impl NativeDoqError {
    fn new(kind: &'static str, message: String) -> Self {
        Self { kind, message }
    }
}

impl From<EncryptedDnsError> for NativeDoqError {
    fn from(error: EncryptedDnsError) -> Self {
        let kind = match error.kind() {
            EncryptedDnsErrorKind::Timeout => "timeout",
            EncryptedDnsErrorKind::Tls | EncryptedDnsErrorKind::SniBlocked => "tls",
            _ => "quic",
        };
        Self::new(kind, error.to_string())
    }
}

#[cfg(test)]
mod tests {
    use serde_json::Value;

    use super::*;

    #[test]
    fn cloudflare_doq_exchange_is_available_when_network_tests_enabled() {
        if std::env::var("RIPDPI_RUN_NETWORK_TESTS").as_deref() != Ok("1") {
            return;
        }
        let query = build_dns_query("example.com", 0x1234);
        let request = serde_json::json!({
            "endpoint": "1.1.1.1",
            "port": 853,
            "tlsServerName": "cloudflare-dns.com",
            "queryBase64": STANDARD.encode(query),
            "timeoutMs": 6_000,
        });

        let payload = exchange(&request.to_string());
        let response: Value = serde_json::from_str(&payload).expect("native DoQ response JSON");

        assert_eq!(response.get("error").and_then(Value::as_str), None);
        let response_bytes = STANDARD
            .decode(response.get("responseBase64").and_then(Value::as_str).expect("DoQ response body"))
            .expect("base64 response");
        assert_eq!(response_bytes[0], 0x12);
        assert_eq!(response_bytes[1], 0x34);
    }

    fn build_dns_query(domain: &str, query_id: u16) -> Vec<u8> {
        let mut packet = Vec::with_capacity(512);
        packet.extend(query_id.to_be_bytes());
        packet.extend(0x0100u16.to_be_bytes());
        packet.extend(1u16.to_be_bytes());
        packet.extend(0u16.to_be_bytes());
        packet.extend(0u16.to_be_bytes());
        packet.extend(0u16.to_be_bytes());
        for label in domain.trim_end_matches('.').split('.') {
            packet.push(label.len() as u8);
            packet.extend(label.as_bytes());
        }
        packet.push(0);
        packet.extend(1u16.to_be_bytes());
        packet.extend(1u16.to_be_bytes());
        packet
    }
}
