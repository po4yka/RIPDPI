use std::collections::BTreeMap;
use std::future::Future;
use std::io;
use std::net::SocketAddr;
use std::os::fd::AsRawFd;
use std::time::Duration;

use android_support::authority_header_value;
use boring::ssl::SslVersion;
use bytes::Bytes;
use http::header::HOST;
use http::{Method, Request};
use http_body_util::{BodyExt, Full};
use hyper::client::conn::http1;
use hyper_util::rt::TokioIo;
use jni::objects::JString;
use jni::{EnvUnowned, Outcome};
use ripdpi_tls_profiles::configure_builder;
use serde::{Deserialize, Serialize};
use socket2::{Domain, Protocol, SockAddr, Socket, Type};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::{TcpStream, lookup_host};
use url::Url;

const HTTP11_ALPN: &[u8] = b"\x08http/1.1";
const CHROME_STABLE_PROFILE: &str = "chrome_stable";
const MAX_PROVISIONING_RESPONSE_BODY_BYTES: usize = 1024 * 1024;
const WARP_PROVISIONING_TIMEOUT: Duration = Duration::from_secs(60);
const WARP_CONNECT_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Debug, Deserialize)]
pub struct NativeWarpProvisioningHttpRequest {
    pub method: String,
    pub url: String,
    #[serde(default)]
    pub headers: BTreeMap<String, String>,
    #[serde(default)]
    pub body: Option<String>,
    #[serde(default)]
    pub proxy: Option<NativeWarpProvisioningProxyConfig>,
}

#[derive(Debug, Deserialize)]
pub struct NativeWarpProvisioningProxyConfig {
    pub host: String,
    pub port: u16,
}

#[derive(Debug, Serialize)]
pub struct NativeWarpProvisioningHttpResponse {
    #[serde(rename = "statusCode")]
    pub status_code: Option<u16>,
    pub body: Option<String>,
    pub error: Option<String>,
}

pub(crate) fn execute_from_jni(mut env: EnvUnowned<'_>, request_json: JString<'_>) -> jni::sys::jstring {
    match env
        .with_env(move |env| -> jni::errors::Result<jni::sys::jstring> {
            let request_json: String = request_json.mutf8_chars(env)?.to_str().into_owned();
            let payload = execute(&request_json).unwrap_or_else(|error| {
                serde_json::json!({
                    "error": error.to_string(),
                })
                .to_string()
            });
            Ok(env.new_string(payload)?.into_raw())
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        _ => std::ptr::null_mut(),
    }
}

fn execute(request_json: &str) -> io::Result<String> {
    let request: NativeWarpProvisioningHttpRequest =
        serde_json::from_str(request_json).map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?;
    let runtime = tokio::runtime::Builder::new_current_thread().enable_all().build().map_err(io::Error::other)?;
    let response = runtime.block_on(run_with_provisioning_timeout(execute_async(request), WARP_PROVISIONING_TIMEOUT));
    runtime.shutdown_background();
    let payload = match response {
        Ok(response) => response,
        Err(error) => {
            NativeWarpProvisioningHttpResponse { status_code: None, body: None, error: Some(error.to_string()) }
        }
    };
    serde_json::to_string(&payload).map_err(io::Error::other)
}

async fn run_with_provisioning_timeout<F, T>(future: F, timeout_duration: Duration) -> io::Result<T>
where
    F: Future<Output = io::Result<T>>,
{
    tokio::time::timeout(timeout_duration, future)
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "WARP provisioning request timed out"))?
}

/// NOT cancel-safe: the remote registration or refresh may already be applied when a deadline drops the local exchange, so a timeout is an indeterminate remote outcome and does not prove server-side rollback.
async fn execute_async(request: NativeWarpProvisioningHttpRequest) -> io::Result<NativeWarpProvisioningHttpResponse> {
    let parsed = Url::parse(&request.url)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid URL: {error}")))?;
    let host = parsed
        .host_str()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "WARP provisioning URL has no host"))?
        .to_string();
    let port = parsed.port_or_known_default().unwrap_or(443);
    let path = parsed.path().to_string();
    let query_suffix = parsed.query().map(|query| format!("?{query}")).unwrap_or_default();
    let target_path = format!("{path}{query_suffix}");

    let tcp = connect_transport(&host, port, request.proxy.as_ref()).await?;
    tcp.set_nodelay(true)?;

    let mut connector =
        configure_builder(CHROME_STABLE_PROFILE).map_err(|error| io::Error::other(format!("TLS profile: {error}")))?;
    connector
        .set_min_proto_version(Some(SslVersion::TLS1_2))
        .map_err(|error| io::Error::other(format!("TLS min version: {error}")))?;
    connector
        .set_max_proto_version(Some(SslVersion::TLS1_2))
        .map_err(|error| io::Error::other(format!("TLS max version: {error}")))?;
    connector.set_alpn_protos(HTTP11_ALPN).map_err(|error| io::Error::other(format!("TLS ALPN: {error}")))?;

    let ssl = connector.build().configure().map_err(|error| io::Error::other(format!("TLS configure: {error}")))?;
    let tls = tokio_boring::connect(ssl, &host, tcp)
        .await
        .map_err(|error| io::Error::new(io::ErrorKind::ConnectionRefused, format!("TLS handshake failed: {error}")))?;

    let (mut sender, connection) = http1::handshake(TokioIo::new(tls))
        .await
        .map_err(|error| io::Error::new(io::ErrorKind::ConnectionAborted, format!("HTTP handshake failed: {error}")))?;
    tokio::spawn(async move {
        let _ = connection.await;
    });

    let method = Method::from_bytes(request.method.as_bytes())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid method: {error}")))?;
    let body = Bytes::from(request.body.unwrap_or_default());
    let mut builder = Request::builder().method(method).uri(target_path);
    let mut has_host_header = false;
    for (name, value) in request.headers {
        if name.eq_ignore_ascii_case(HOST.as_str()) {
            has_host_header = true;
        }
        builder = builder.header(name.as_str(), value.as_str());
    }
    if !has_host_header {
        builder = builder.header(HOST, authority_header_value(&host, port, true));
    }
    let request = builder
        .body(Full::new(body))
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid request body: {error}")))?;

    let response = sender
        .send_request(request)
        .await
        .map_err(|error| io::Error::new(io::ErrorKind::ConnectionAborted, format!("request failed: {error}")))?;
    let status_code = response.status().as_u16();
    let body = collect_response_body(response.into_body(), MAX_PROVISIONING_RESPONSE_BODY_BYTES).await?;
    Ok(NativeWarpProvisioningHttpResponse { status_code: Some(status_code), body: Some(body), error: None })
}

async fn collect_response_body<B>(mut body: B, max_bytes: usize) -> io::Result<String>
where
    B: hyper::body::Body<Data = Bytes> + Unpin,
    B::Error: std::fmt::Display,
{
    if body.size_hint().upper().is_some_and(|upper| usize::try_from(upper).map_or(true, |upper| upper > max_bytes)) {
        return Err(provisioning_response_body_too_large(max_bytes));
    }

    let mut bytes = Vec::new();
    while let Some(frame) = body.frame().await {
        let frame = frame.map_err(|error| {
            io::Error::new(io::ErrorKind::ConnectionAborted, format!("response body failed: {error}"))
        })?;
        let Ok(data) = frame.into_data() else {
            continue;
        };
        let new_len =
            bytes.len().checked_add(data.len()).ok_or_else(|| provisioning_response_body_too_large(max_bytes))?;
        if new_len > max_bytes {
            return Err(provisioning_response_body_too_large(max_bytes));
        }
        bytes.extend_from_slice(&data);
    }

    String::from_utf8(bytes)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidData, format!("response body was not UTF-8: {error}")))
}

fn provisioning_response_body_too_large(max_bytes: usize) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidData, format!("WARP provisioning response body exceeds {max_bytes} bytes"))
}

async fn connect_transport(
    host: &str,
    port: u16,
    proxy: Option<&NativeWarpProvisioningProxyConfig>,
) -> io::Result<TcpStream> {
    match proxy {
        Some(proxy) => {
            let mut stream = connect_protected(proxy.host.as_str(), proxy.port).await?;
            socks5_handshake_no_auth(&mut stream).await?;
            socks5_connect_domain(&mut stream, host, port).await?;
            Ok(stream)
        }
        None => connect_protected(host, port).await,
    }
}

/// Open a TCP connection that is protected from the VPN's own TUN route.
///
/// This mirrors the create-protect-connect ordering in
/// `ripdpi-warp-core::wireguard::socket::bind_tunnel_socket`: the socket fd is
/// handed to [`ripdpi_native_protect::protect_socket_via_callback`] BEFORE
/// `connect()` so the kernel binds it to the underlying network rather than
/// looping its traffic back into the TUN device. See
/// `.claude/rules/vpnservice-protect-invariant.md`.
///
/// `register()`/`refresh()` provisioning can run while the VPN is active, so
/// this path must honor the invariant. When no protect callback is registered
/// (the genuine off-TUN case) the helper proceeds best-effort, matching
/// `protect_socket_if_configured` in warp-core.
async fn connect_protected(host: &str, port: u16) -> io::Result<TcpStream> {
    let mut last_err: Option<io::Error> =
        Some(io::Error::new(io::ErrorKind::AddrNotAvailable, format!("no addresses resolved for {host}:{port}")));
    for addr in lookup_host((host, port)).await? {
        match connect_protected_addr(addr).await {
            Ok(stream) => return Ok(stream),
            Err(error) => last_err = Some(error),
        }
    }
    Err(last_err.unwrap_or_else(|| io::Error::new(io::ErrorKind::AddrNotAvailable, "connect failed")))
}

async fn connect_protected_addr(addr: SocketAddr) -> io::Result<TcpStream> {
    // Build, protect, and connect on a blocking socket inside `spawn_blocking`,
    // then hand the connected fd to tokio. Keeping the connect blocking avoids
    // the nonblocking `EINPROGRESS` dance (which is not portably distinguishable
    // from a fatal error without a `libc` dependency) while preserving the
    // create-protect-connect ordering the invariant requires. The socket deadline
    // bounds blocking work that Tokio cannot cancel after the outer request
    // deadline fires.
    let std_stream = tokio::task::spawn_blocking(move || -> io::Result<std::net::TcpStream> {
        let socket = Socket::new(Domain::for_address(addr), Type::STREAM, Some(Protocol::TCP))?;
        // Protect BEFORE connect: bind the fd to the underlying network so its
        // traffic is not captured by the VPN's own TUN route. NotConnected means
        // no callback is registered (off-TUN provisioning) — proceed best-effort.
        match ripdpi_native_protect::protect_socket_via_callback(socket.as_raw_fd()) {
            Ok(()) => {}
            Err(error) if error.kind() == io::ErrorKind::NotConnected => {}
            Err(error) => return Err(error),
        }
        socket.connect_timeout(&SockAddr::from(addr), WARP_CONNECT_TIMEOUT)?;
        Ok(socket.into())
    })
    .await
    .map_err(io::Error::other)??;
    std_stream.set_nonblocking(true)?;
    TcpStream::from_std(std_stream)
}

async fn socks5_handshake_no_auth(stream: &mut TcpStream) -> io::Result<()> {
    stream.write_all(&[0x05, 0x01, 0x00]).await?;
    let mut response = [0u8; 2];
    stream.read_exact(&mut response).await?;
    if response != [0x05, 0x00] {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            format!("SOCKS5 handshake rejected method {response:?}"),
        ));
    }
    Ok(())
}

async fn socks5_connect_domain(stream: &mut TcpStream, host: &str, port: u16) -> io::Result<()> {
    let host_len =
        u8::try_from(host.len()).map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "SOCKS5 host is too long"))?;
    let mut request = Vec::with_capacity(7 + host.len());
    request.extend_from_slice(&[0x05, 0x01, 0x00, 0x03, host_len]);
    request.extend_from_slice(host.as_bytes());
    request.extend_from_slice(&port.to_be_bytes());
    stream.write_all(&request).await?;

    let mut header = [0u8; 4];
    stream.read_exact(&mut header).await?;
    if header[1] != 0x00 {
        return Err(io::Error::new(
            io::ErrorKind::ConnectionRefused,
            format!("SOCKS5 CONNECT failed with REP={:#04x}", header[1]),
        ));
    }
    match header[3] {
        0x01 => {
            let mut rest = [0u8; 6];
            stream.read_exact(&mut rest).await?;
        }
        0x04 => {
            let mut rest = [0u8; 18];
            stream.read_exact(&mut rest).await?;
        }
        0x03 => {
            let mut length = [0u8; 1];
            stream.read_exact(&mut length).await?;
            let mut rest = vec![0u8; usize::from(length[0]) + 2];
            stream.read_exact(&mut rest).await?;
        }
        atyp => {
            return Err(io::Error::new(
                io::ErrorKind::InvalidData,
                format!("SOCKS5 CONNECT returned invalid ATYP={atyp:#04x}"),
            ));
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use std::collections::VecDeque;
    use std::pin::Pin;
    use std::sync::Arc;
    use std::sync::atomic::{AtomicUsize, Ordering};
    use std::task::{Context, Poll};

    use hyper::body::{Body, Frame, SizeHint};

    use super::*;

    struct TestBody {
        frames: VecDeque<Bytes>,
        upper: Option<u64>,
        poll_count: Arc<AtomicUsize>,
    }

    impl TestBody {
        fn new(frames: impl IntoIterator<Item = Bytes>, upper: Option<u64>, poll_count: Arc<AtomicUsize>) -> Self {
            Self { frames: frames.into_iter().collect(), upper, poll_count }
        }
    }

    impl Body for TestBody {
        type Data = Bytes;
        type Error = io::Error;

        fn poll_frame(
            mut self: Pin<&mut Self>,
            _cx: &mut Context<'_>,
        ) -> Poll<Option<Result<Frame<Self::Data>, Self::Error>>> {
            // Ordering: the counter only observes poll frequency; it does not publish or guard data.
            self.poll_count.fetch_add(1, Ordering::Relaxed);
            Poll::Ready(self.frames.pop_front().map(|bytes| Ok(Frame::data(bytes))))
        }

        fn size_hint(&self) -> SizeHint {
            let mut hint = SizeHint::new();
            if let Some(upper) = self.upper {
                hint.set_upper(upper);
            }
            hint
        }
    }

    #[tokio::test]
    async fn known_oversize_body_is_rejected_without_polling() {
        let poll_count = Arc::new(AtomicUsize::new(0));
        let body = TestBody::new([Bytes::from_static(b"oversize")], Some(9), Arc::clone(&poll_count));

        let error = collect_response_body(body, 8).await.expect_err("known oversize body must be rejected");

        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
        assert!(error.to_string().contains("WARP provisioning response body exceeds"));
        // Ordering: the counter is read after the body future completes and is only test instrumentation.
        assert_eq!(poll_count.load(Ordering::Relaxed), 0);
    }

    #[tokio::test]
    async fn unknown_oversize_body_is_rejected_when_crossing_frame_arrives() {
        let poll_count = Arc::new(AtomicUsize::new(0));
        let body =
            TestBody::new([Bytes::from_static(b"12345"), Bytes::from_static(b"6789")], None, Arc::clone(&poll_count));

        let error = collect_response_body(body, 8).await.expect_err("cumulative oversize body must be rejected");

        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
        assert!(error.to_string().contains("WARP provisioning response body exceeds"));
        // Ordering: the counter is read after the body future completes and is only test instrumentation.
        assert_eq!(poll_count.load(Ordering::Relaxed), 2);
    }

    #[tokio::test]
    async fn exact_limit_across_frames_returns_identical_utf8() {
        let poll_count = Arc::new(AtomicUsize::new(0));
        let body = TestBody::new([Bytes::from_static(b"123"), Bytes::from_static(b"45678")], None, poll_count);

        let result = collect_response_body(body, 8).await.expect("exact-limit body must be accepted");

        assert_eq!(result, "12345678");
    }

    #[tokio::test]
    async fn below_limit_invalid_utf8_preserves_invalid_data_error() {
        let poll_count = Arc::new(AtomicUsize::new(0));
        let body = TestBody::new([Bytes::from_static(&[0xff])], None, poll_count);

        let error = collect_response_body(body, 8).await.expect_err("invalid UTF-8 must be rejected");

        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
        assert!(error.to_string().contains("response body was not UTF-8"));
    }

    #[tokio::test]
    async fn provisioning_timeout_returns_ready_value() {
        let result = run_with_provisioning_timeout(
            std::future::ready(Ok::<_, io::Error>(42)),
            std::time::Duration::from_millis(10),
        )
        .await
        .expect("ready provisioning result must be returned");

        assert_eq!(result, 42);
    }

    #[tokio::test]
    async fn provisioning_timeout_preserves_source_error() {
        let error = run_with_provisioning_timeout(
            std::future::ready(Err::<(), _>(io::Error::new(io::ErrorKind::InvalidData, "source error"))),
            std::time::Duration::from_millis(10),
        )
        .await
        .expect_err("source error must be preserved");

        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
        assert_eq!(error.to_string(), "source error");
    }

    #[tokio::test]
    async fn provisioning_timeout_rejects_pending_work() {
        let error = run_with_provisioning_timeout(
            std::future::pending::<io::Result<()>>(),
            std::time::Duration::from_millis(1),
        )
        .await
        .expect_err("pending provisioning work must time out");

        assert_eq!(error.kind(), io::ErrorKind::TimedOut);
        assert!(error.to_string().contains("WARP provisioning request timed out"));
    }
}
