use std::io;
use std::net::IpAddr;
use std::os::fd::AsRawFd;
use std::time::{Duration, Instant};

use base64::Engine;
use base64::engine::general_purpose::STANDARD;
use jni::objects::JString;
use jni::sys::jstring;
use jni::{EnvUnowned, Outcome};
use ripdpi_dns_resolver::{
    DoqErrorKind, EncryptedDnsConnectHooks, EncryptedDnsEndpoint, EncryptedDnsError, EncryptedDnsProtocol,
    EncryptedDnsResolver, EncryptedDnsTransport,
};
use ripdpi_runtime_platform::protect::{has_protect_callback, protect_socket_via_callback};
use serde::{Deserialize, Serialize};
use socket2::{Domain, Protocol, Socket, Type};
use tokio::time::timeout_at;

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
        Err(error) => {
            return response_error(NativeDoqErrorKind::InvalidRequest, format!("invalid native DoQ request: {error}"));
        }
    };
    let query = match STANDARD.decode(request.query_base64.trim()) {
        Ok(query) => query,
        Err(error) => {
            return response_error(NativeDoqErrorKind::InvalidRequest, format!("invalid DoQ query encoding: {error}"));
        }
    };
    let limit = Duration::from_millis(request.timeout_ms.max(1));
    let Some(deadline) = Instant::now().checked_add(limit) else {
        return response_error(NativeDoqErrorKind::InvalidRequest, "invalid DoQ timeout");
    };
    match run_bounded(deadline, exchange_async(request, query, limit, deadline)) {
        Ok(Some(Ok(response))) => response_ok(response),
        Ok(Some(Err(error))) => response_error(error.kind, error.message),
        Ok(None) => response_error(NativeDoqErrorKind::Timeout, "native DoQ exchange timed out"),
        Err(error) => response_error(NativeDoqErrorKind::Setup, format!("build native DoQ runtime: {error}")),
    }
}

/// Run `future` on a fresh current-thread runtime until `deadline`;
/// `Ok(None)` means it timed out. The runtime shuts down in the background:
/// dropping it would wait for in-flight blocking tasks such as the
/// uncancellable `getaddrinfo` behind `lookup_host`, holding the JNI caller far
/// past `limit`.
fn run_bounded<F: std::future::Future>(deadline: Instant, future: F) -> io::Result<Option<F::Output>> {
    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build()?;
    let result = runtime.block_on(async { timeout_at(deadline.into(), future).await }).ok();
    runtime.shutdown_background();
    Ok(result)
}

/// # Cancel safety:
/// cancel-safe: dropping the exchange releases its resolver and any owned streams.
async fn exchange_async(
    request: NativeDoqExchangeRequest,
    query: Vec<u8>,
    timeout_duration: Duration,
    deadline: Instant,
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
            odoh: None,
        },
        EncryptedDnsTransport::Direct,
        timeout_duration,
        doq_connect_hooks(),
    )
    .map_err(NativeDoqError::from)?;
    resolver.exchange_doq_until(&query, deadline).await.map_err(NativeDoqError::from)
}

/// # Cancel safety:
/// cancel-safe: cancelling the wait does not cancel an in-flight platform lookup.
async fn bootstrap_ips(endpoint: &str, port: u16) -> Result<Vec<IpAddr>, NativeDoqError> {
    if let Ok(ip) = endpoint.parse::<IpAddr>() {
        return Ok(vec![ip]);
    }
    // Async DNS via tokio's blocking pool — avoids blocking the current_thread
    // runtime worker (which is the only worker on this JNI-spawned runtime, so
    // a sync `to_socket_addrs` would defeat the outer `tokio::time::timeout`
    // because the timer cannot fire while the worker is parked in libc resolve).
    let addresses = tokio::net::lookup_host((endpoint, port)).await.map_err(|error| {
        NativeDoqError::new(NativeDoqErrorKind::Bootstrap, format!("resolve DoQ bootstrap {endpoint}: {error}"))
    })?;
    let ips: Vec<IpAddr> = addresses.map(|addr| addr.ip()).collect();
    if ips.is_empty() {
        return Err(NativeDoqError::new(
            NativeDoqErrorKind::Bootstrap,
            format!("resolve DoQ bootstrap {endpoint}: no addresses"),
        ));
    }
    Ok(ips)
}

fn doq_connect_hooks() -> EncryptedDnsConnectHooks {
    EncryptedDnsConnectHooks::new().with_direct_udp_binder(|bind_addr| {
        let socket = Socket::new(Domain::for_address(bind_addr), Type::DGRAM, Some(Protocol::UDP))?;
        if bind_addr.is_ipv6() {
            socket.set_only_v6(false)?;
        }
        if has_protect_callback() {
            protect_socket_via_callback(socket.as_raw_fd())
                .map_err(|error| io::Error::new(error.kind(), format!("protect native DoQ socket: {error}")))?;
        }
        socket.bind(&bind_addr.into())?;
        Ok(socket.into())
    })
}

fn response_ok(response: Vec<u8>) -> String {
    serde_json::to_string(&NativeDoqExchangeResponse {
        response_base64: Some(STANDARD.encode(response)),
        error_kind: None,
        error: None,
    })
    .unwrap_or_else(|error| {
        response_error(NativeDoqErrorKind::Setup, format!("serialize native DoQ response: {error}"))
    })
}

fn response_error(error_kind: NativeDoqErrorKind, error: impl Into<String>) -> String {
    serde_json::to_string(&NativeDoqExchangeResponse {
        response_base64: None,
        error_kind: Some(error_kind),
        error: Some(error.into()),
    })
    .unwrap_or_else(|_| "{\"errorKind\":\"setup\",\"error\":\"serialize native DoQ error\"}".to_string())
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
    error_kind: Option<NativeDoqErrorKind>,
    error: Option<String>,
}

#[derive(Debug, Clone, Copy, Serialize)]
#[serde(rename_all = "snake_case")]
enum NativeDoqErrorKind {
    InvalidRequest,
    Setup,
    Bootstrap,
    Tls,
    Alpn,
    Quic,
    Wire,
    Timeout,
}

struct NativeDoqError {
    kind: NativeDoqErrorKind,
    message: String,
}

impl NativeDoqError {
    fn new(kind: NativeDoqErrorKind, message: String) -> Self {
        Self { kind, message }
    }
}

impl From<EncryptedDnsError> for NativeDoqError {
    fn from(error: EncryptedDnsError) -> Self {
        let kind = match &error {
            EncryptedDnsError::Doq { kind, .. } => match kind {
                DoqErrorKind::Setup => NativeDoqErrorKind::Setup,
                DoqErrorKind::Connect | DoqErrorKind::Exchange => NativeDoqErrorKind::Quic,
                DoqErrorKind::Tls => NativeDoqErrorKind::Tls,
                DoqErrorKind::Alpn => NativeDoqErrorKind::Alpn,
                DoqErrorKind::ConnectTimeout | DoqErrorKind::QueryTimeout => NativeDoqErrorKind::Timeout,
            },
            EncryptedDnsError::MissingBootstrapIps => NativeDoqErrorKind::Bootstrap,
            EncryptedDnsError::InvalidEndpoint(_) | EncryptedDnsError::MissingHost => {
                NativeDoqErrorKind::InvalidRequest
            }
            EncryptedDnsError::DnsParse(_) | EncryptedDnsError::DnsMessageTooLarge { .. } => NativeDoqErrorKind::Wire,
            _ => NativeDoqErrorKind::Setup,
        };
        Self::new(kind, error.to_string())
    }
}

#[cfg(test)]
mod tests {
    use serde_json::Value;

    use super::*;

    #[test]
    fn doq_bridge_preserves_native_error_kinds() {
        let native_errors = [
            (DoqErrorKind::Setup, "setup"),
            (DoqErrorKind::Connect, "quic"),
            (DoqErrorKind::Exchange, "quic"),
            (DoqErrorKind::Tls, "tls"),
            (DoqErrorKind::Alpn, "alpn"),
            (DoqErrorKind::ConnectTimeout, "timeout"),
            (DoqErrorKind::QueryTimeout, "timeout"),
        ];
        for (kind, expected) in native_errors {
            let error = NativeDoqError::from(EncryptedDnsError::Doq { kind, message: "failure".into() });
            let result: Value = serde_json::from_str(&response_error(error.kind, error.message)).unwrap();
            assert_eq!(result["errorKind"], expected);
        }
        let wire_error = NativeDoqError::from(EncryptedDnsError::DnsParse("short response".into()));
        let result: Value = serde_json::from_str(&response_error(wire_error.kind, wire_error.message)).unwrap();
        assert_eq!(result["errorKind"], "wire");
    }

    #[test]
    fn doq_udp_is_protected_before_bind() {
        struct UnboundSocketRequired;
        impl ripdpi_runtime_platform::protect::ProtectCallback for UnboundSocketRequired {
            fn protect(&self, fd: std::os::fd::RawFd) -> io::Result<()> {
                let local =
                    nix::sys::socket::getsockname::<nix::sys::socket::SockaddrStorage>(fd).map_err(io::Error::from)?;
                let port = local
                    .as_sockaddr_in()
                    .map(nix::sys::socket::SockaddrIn::port)
                    .or_else(|| local.as_sockaddr_in6().map(nix::sys::socket::SockaddrIn6::port))
                    .expect("IP socket");
                if port != 0 {
                    return Err(io::Error::new(
                        io::ErrorKind::InvalidInput,
                        "protect received an already bound socket",
                    ));
                }
                Ok(())
            }
        }
        ripdpi_runtime_platform::protect::register_protect_callback(std::sync::Arc::new(UnboundSocketRequired));
        let hooks = doq_connect_hooks();
        let binder = hooks.direct_udp_binder.as_ref().expect("DoQ binder");
        let results = ["0.0.0.0:0", "[::]:0"].map(|address| binder(address.parse().expect("bind address")));
        ripdpi_runtime_platform::protect::unregister_protect_callback();
        for result in results {
            assert_ne!(result.expect("protect before bind").local_addr().expect("bound").port(), 0);
        }
    }

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

    #[test]
    fn bounded_run_returns_at_the_deadline_while_a_blocking_lookup_runs() {
        let started = std::time::Instant::now();
        let result = run_bounded(started + Duration::from_millis(50), async {
            // Stands in for getaddrinfo on a blackholed resolver.
            tokio::task::spawn_blocking(|| std::thread::sleep(Duration::from_secs(3))).await
        })
        .expect("runtime");

        assert!(result.is_none(), "the future must time out");
        assert!(started.elapsed() < Duration::from_secs(1), "returned after {:?}", started.elapsed());
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
