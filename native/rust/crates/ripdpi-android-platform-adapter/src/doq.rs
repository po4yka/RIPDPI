use std::future::Future;
use std::io;
use std::net::{IpAddr, ToSocketAddrs};
use std::os::fd::AsRawFd;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
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
use tokio::sync::Semaphore;
use tokio::time::{sleep, timeout};

const DNS_HEADER_BYTES: usize = 12;
const MAX_DNS_MESSAGE_BYTES: usize = u16::MAX as usize;
const MAX_QUERY_BASE64_BYTES: usize = MAX_DNS_MESSAGE_BYTES.div_ceil(3) * 4;
const MAX_REQUEST_BYTES: usize = MAX_QUERY_BASE64_BYTES + 1024;

const CANCELLATION_POLL: Duration = Duration::from_millis(25);
const STREAM_CLEANUP_GRACE: Duration = Duration::from_millis(25);
const MAX_BOOTSTRAP_ADDRESSES: usize = 8;
static BOOTSTRAP_LOOKUPS: Semaphore = Semaphore::const_new(8);
static ACTIVE_EXCHANGES: Semaphore = Semaphore::const_new(8);

/// The facade owns request lifetimes alongside other JNI handles.
pub struct DoqRequestControls {
    pub reserve: fn() -> Option<u64>,
    pub cancel: fn(u64),
    pub release: fn(u64),
    pub cancellation: fn(u64) -> Option<Arc<AtomicBool>>,
}

pub fn exchange_entry(mut env: EnvUnowned<'_>, request_json: JString<'_>, controls: &DoqRequestControls) -> jstring {
    match env
        .with_env(move |env| -> jni::errors::Result<jstring> {
            let request_json = {
                let chars = request_json.mutf8_chars(env)?;
                if chars.to_bytes().len() > MAX_REQUEST_BYTES {
                    let payload = response_error(NativeDoqErrorKind::InvalidRequest, "native DoQ request is too large");
                    return Ok(env.new_string(payload)?.into_raw());
                }
                chars.to_str().into_owned()
            };
            let (payload, reserved) = exchange(&request_json, controls);
            let reply = env.new_string(payload);
            if reply.is_err()
                && let Some(handle) = reserved
            {
                (controls.release)(handle);
            }
            Ok(reply?.into_raw())
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        _ => std::ptr::null_mut(),
    }
}

// Return a new reservation separately so JNI can release it if the reply cannot be delivered.
fn exchange(request_json: &str, controls: &DoqRequestControls) -> (String, Option<u64>) {
    if request_json.len() > MAX_REQUEST_BYTES {
        return (response_error(NativeDoqErrorKind::InvalidRequest, "native DoQ request is too large"), None);
    }
    let request = match serde_json::from_str::<NativeDoqRequest>(request_json) {
        Ok(NativeDoqRequest::Control(control)) => return control_reply(control, controls),
        Ok(NativeDoqRequest::Exchange(request)) => request,
        Err(error) => {
            return (
                response_error(NativeDoqErrorKind::InvalidRequest, format!("invalid native DoQ request: {error}")),
                None,
            );
        }
    };
    let Some(cancelled) = (controls.cancellation)(request.request_handle) else {
        return (response_error(NativeDoqErrorKind::InvalidRequest, "native DoQ request is not reserved"), None);
    };
    (exchange_reserved(request, &cancelled), None)
}

fn control_reply(control: NativeDoqControl, controls: &DoqRequestControls) -> (String, Option<u64>) {
    const OK: &str = r#"{"controlOk":true}"#;
    match control {
        NativeDoqControl::Reserve => (controls.reserve)().map_or_else(
            || (response_error(NativeDoqErrorKind::Setup, "native DoQ request capacity exhausted"), None),
            |handle| (serde_json::json!({"requestHandle": handle}).to_string(), Some(handle)),
        ),
        NativeDoqControl::Cancel { request_handle } if valid_handle(request_handle) => {
            (controls.cancel)(request_handle);
            (OK.to_owned(), None)
        }
        NativeDoqControl::Release { request_handle } if valid_handle(request_handle) => {
            (controls.release)(request_handle);
            (OK.to_owned(), None)
        }
        _ => (response_error(NativeDoqErrorKind::InvalidRequest, "invalid native DoQ request handle"), None),
    }
}

fn valid_handle(handle: u64) -> bool {
    (1..=i64::MAX as u64).contains(&handle)
}

fn exchange_reserved(request: NativeDoqExchangeRequest, cancelled: &AtomicBool) -> String {
    if request.endpoint.trim().is_empty()
        || request.endpoint.len() > 253
        || request.tls_server_name.trim().is_empty()
        || request.tls_server_name.len() > 253
        || matches!(request.port, 0 | 53)
        || request.timeout_ms == 0
    {
        return response_error(NativeDoqErrorKind::InvalidRequest, "invalid DoQ endpoint or timeout");
    }
    let query = match decode_query(&request.query_base64) {
        Ok(query) => query,
        Err(error) => return response_error(NativeDoqErrorKind::InvalidRequest, error),
    };
    let limit = Duration::from_millis(request.timeout_ms);
    let Some(deadline) = Instant::now().checked_add(limit) else {
        return response_error(NativeDoqErrorKind::InvalidRequest, "invalid DoQ timeout");
    };
    // A released or repeated handle cannot free an in-flight exchange's slot.
    let Ok(_active) = ACTIVE_EXCHANGES.try_acquire() else {
        return response_error(NativeDoqErrorKind::Setup, "native DoQ exchange capacity exhausted");
    };
    match run_on_current_thread(exchange_async(request, query, limit, deadline, cancelled)) {
        Ok(Ok(response)) => response_ok(response),
        Ok(Err(error)) => response_error(error.kind, error.message),
        Err(error) => response_error(NativeDoqErrorKind::Setup, format!("build native DoQ runtime: {error}")),
    }
}

fn decode_query(encoded: &str) -> Result<Vec<u8>, &'static str> {
    if encoded.len() > MAX_QUERY_BASE64_BYTES {
        return Err("encoded DoQ query is too large");
    }
    let query = STANDARD.decode(encoded).map_err(|_| "invalid DoQ query encoding")?;
    if !(DNS_HEADER_BYTES..=MAX_DNS_MESSAGE_BYTES).contains(&query.len()) {
        return Err("invalid DoQ query size");
    }
    Ok(query)
}

// A timed-out platform lookup can still be in libc. Do not join that blocking
// worker on the JNI caller. Its lookup permit stays with it until it returns.
fn run_on_current_thread<T>(future: impl Future<Output = T>) -> io::Result<T> {
    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build()?;
    let result = runtime.block_on(future);
    runtime.shutdown_background();
    Ok(result)
}

/// # Cancel safety:
/// conditionally cancel-safe: dropping the operation must be safe. A polling tick drops only a borrow.
async fn run_cancellable<T>(
    deadline: Instant,
    cancelled: &AtomicBool,
    future: impl Future<Output = Result<T, NativeDoqError>>,
) -> Result<T, NativeDoqError> {
    let mut future = std::pin::pin!(future);
    let mut completed = None;
    loop {
        // The flag does not publish any other state.
        if cancelled.load(Ordering::Relaxed) {
            return Err(NativeDoqError::new(NativeDoqErrorKind::Cancelled, "native DoQ request cancelled"));
        }
        let remaining = deadline.saturating_duration_since(Instant::now());
        if remaining.is_zero() {
            return Err(NativeDoqError::new(NativeDoqErrorKind::Timeout, "native DoQ exchange timed out"));
        }
        if let Some(result) = completed {
            return result;
        }
        // Timeout polls the child first. Check the flag and deadline again before returning its value.
        completed = timeout(remaining.min(CANCELLATION_POLL), future.as_mut()).await.ok();
    }
}

/// # Cancel safety:
/// cancel-safe: dropping the exchange releases its resolver and any owned streams.
async fn exchange_async(
    request: NativeDoqExchangeRequest,
    query: Vec<u8>,
    timeout_duration: Duration,
    deadline: Instant,
    cancelled: &AtomicBool,
) -> Result<Vec<u8>, NativeDoqError> {
    let bootstrap_ips = run_cancellable(deadline, cancelled, bootstrap_ips(&request.endpoint, request.port)).await?;
    #[cfg(all(target_os = "android", feature = "instrumented-diagnostics-tls"))]
    let roots = vec![include_bytes!(env!("RIPDPI_INSTRUMENTED_DIAGNOSTICS_ROOT_DER")).as_slice().into()];
    #[cfg(not(all(target_os = "android", feature = "instrumented-diagnostics-tls")))]
    let roots = Vec::new();
    let resolver = EncryptedDnsResolver::with_extra_tls_roots_and_connect_hooks(
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
        roots,
        doq_connect_hooks(),
    )
    .map_err(NativeDoqError::from)?;
    let result = run_cancellable(deadline, cancelled, async {
        resolver.exchange_doq_until(&query, deadline).await.map_err(NativeDoqError::from)
    })
    .await;
    if matches!(
        result,
        Err(NativeDoqError {
            kind: NativeDoqErrorKind::Cancelled | NativeDoqErrorKind::Timeout | NativeDoqErrorKind::Wire,
            ..
        })
    ) {
        // Keep the resolver alive while Quinn sends STOP_SENDING or CONNECTION_CLOSE.
        sleep(STREAM_CLEANUP_GRACE).await;
    }
    result
}

/// # Cancel safety:
/// cancel-safe: cancelling the wait does not cancel an in-flight platform lookup.
async fn bootstrap_ips(endpoint: &str, port: u16) -> Result<Vec<IpAddr>, NativeDoqError> {
    if let Ok(ip) = endpoint.parse::<IpAddr>() {
        return Ok(vec![ip]);
    }
    let endpoint = endpoint.to_owned();
    let ips = bounded_platform_lookup(&BOOTSTRAP_LOOKUPS, move || platform_bootstrap_ips(&endpoint, port)).await?;
    if ips.is_empty() {
        return Err(NativeDoqError::new(NativeDoqErrorKind::Bootstrap, "DoQ bootstrap returned no addresses"));
    }
    Ok(ips)
}

fn platform_bootstrap_ips(endpoint: &str, port: u16) -> io::Result<Vec<IpAddr>> {
    let mut ips = Vec::new();
    for address in (endpoint, port).to_socket_addrs()? {
        let ip = address.ip();
        if !ips.contains(&ip) {
            ips.push(ip);
        }
        if ips.len() == MAX_BOOTSTRAP_ADDRESSES {
            break;
        }
    }
    Ok(ips)
}

/// # Cancel safety:
/// cancel-safe except for fairness: a cancelled acquisition loses its place in the queue.
/// After a lookup starts, its permit stays with the worker until that worker returns.
async fn bounded_platform_lookup<T: Send + 'static>(
    lookups: &'static Semaphore,
    lookup: impl FnOnce() -> io::Result<T> + Send + 'static,
) -> Result<T, NativeDoqError> {
    let permit = lookups
        .acquire()
        .await
        .map_err(|_| NativeDoqError::new(NativeDoqErrorKind::Setup, "native DoQ bootstrap queue closed"))?;
    tokio::task::spawn_blocking(move || {
        let _permit = permit;
        lookup()
    })
    .await
    .map_err(|_| NativeDoqError::new(NativeDoqErrorKind::Setup, "native DoQ bootstrap worker failed"))?
    .map_err(|error| NativeDoqError::new(NativeDoqErrorKind::Bootstrap, format!("resolve DoQ bootstrap: {error}")))
}

fn doq_connect_hooks() -> EncryptedDnsConnectHooks {
    EncryptedDnsConnectHooks::new().with_direct_udp_binder(|bind_addr| {
        let socket = Socket::new(Domain::for_address(bind_addr), Type::DGRAM, Some(Protocol::UDP))?;
        if bind_addr.is_ipv6() {
            socket.set_only_v6(false)?;
        }
        if has_protect_callback() {
            // Android protection is synchronous. The deadline is checked after setup.
            protect_socket_via_callback(socket.as_raw_fd())
                .map_err(|error| io::Error::new(error.kind(), format!("protect native DoQ socket: {error}")))?;
        }
        socket.bind(&bind_addr.into())?;
        Ok(socket.into())
    })
}

fn response_ok(response: Vec<u8>) -> String {
    if !(DNS_HEADER_BYTES..=MAX_DNS_MESSAGE_BYTES).contains(&response.len()) {
        return response_error(NativeDoqErrorKind::Wire, "invalid DoQ response size");
    }
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
#[serde(untagged)]
enum NativeDoqRequest {
    Control(NativeDoqControl),
    Exchange(NativeDoqExchangeRequest),
}

#[derive(Debug, Deserialize)]
#[serde(tag = "operation", rename_all = "snake_case", deny_unknown_fields)]
enum NativeDoqControl {
    Reserve,
    Cancel {
        #[serde(rename = "requestHandle")]
        request_handle: u64,
    },
    Release {
        #[serde(rename = "requestHandle")]
        request_handle: u64,
    },
}

#[derive(Debug, Deserialize)]
#[serde(deny_unknown_fields)]
struct NativeDoqExchangeRequest {
    #[serde(rename = "requestHandle")]
    request_handle: u64,
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
    Cancelled,
}

#[derive(Debug)]
struct NativeDoqError {
    kind: NativeDoqErrorKind,
    message: String,
}

impl NativeDoqError {
    fn new(kind: NativeDoqErrorKind, message: impl Into<String>) -> Self {
        Self { kind, message: message.into() }
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
#[path = "doq_tests.rs"]
mod tests;
