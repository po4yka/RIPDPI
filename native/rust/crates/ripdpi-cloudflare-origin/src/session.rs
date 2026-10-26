use std::io;
use std::sync::atomic::{AtomicU64, Ordering};
use std::time::Duration;

use bytes::Bytes;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpStream;
use tokio::sync::mpsc;
use tokio::time::{Instant, sleep_until, timeout};

const STREAM_BUFFER_SIZE: usize = 16 * 1024;
/// How long a started session may wait for its VLESS header. The session is
/// unauthenticated until then, so this is short.
const VLESS_HEADER_TIMEOUT: Duration = Duration::from_secs(10);
/// Deadline for the upstream TCP connect; a blackholed target otherwise holds
/// a session slot for the whole kernel SYN-retry window.
const UPSTREAM_CONNECT_TIMEOUT: Duration = Duration::from_secs(10);
/// A session with no traffic in either direction for this long ends, as with
/// Xray's default `connIdle` policy. A half-open upstream never errors.
const SESSION_IDLE_TIMEOUT: Duration = Duration::from_secs(300);

/// Reads the VLESS header, checks the UUID, then relays to the target.
/// `on_authenticated` runs once the UUID matches; when it returns false (the
/// session was evicted first) the session ends before it connects upstream.
///
/// The session ends when the upstream closes or fails, when the GET stream
/// is gone, when an upstream write fails, or after `SESSION_IDLE_TIMEOUT`
/// without traffic. The end of the POST body only half-closes the upstream.
///
/// # Cancel safety
///
/// cancel-safe: both relay halves run inside this future, so dropping it
/// drops the channels and closes the upstream socket.
pub(crate) async fn run_session(
    mut inbound_rx: mpsc::Receiver<Bytes>,
    outbound_tx: mpsc::Sender<io::Result<Bytes>>,
    expected_uuid: [u8; 16],
    protect_path: Option<&str>,
    on_authenticated: impl FnOnce() -> bool,
) -> io::Result<()> {
    let (decoded, buffered_payload) = read_request_header_with_timeout(&mut inbound_rx, VLESS_HEADER_TIMEOUT).await?;
    if decoded.uuid != expected_uuid {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "VLESS UUID does not match configured tunnel identity",
        ));
    }
    if !on_authenticated() {
        return Err(io::Error::new(
            io::ErrorKind::ConnectionAborted,
            "xHTTP session was evicted before authentication",
        ));
    }

    let upstream = timeout(
        UPSTREAM_CONNECT_TIMEOUT,
        ripdpi_subprocess_protect::protected_tcp_connect(decoded.target.as_str(), protect_path),
    )
    .await
    .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "upstream connect timed out"))?
    .map_err(redact_upstream_connect_error)?;
    upstream.set_nodelay(true)?;
    if outbound_tx.send(Ok(Bytes::from(ripdpi_vless::wire::encode_response(&[])?))).await.is_err() {
        return Ok(());
    }
    relay(upstream, inbound_rx, buffered_payload, outbound_tx).await
}

/// Copies POST chunks to the upstream and upstream bytes to the GET stream.
///
/// # Cancel safety
///
/// cancel-safe: every await is a channel or socket operation of this
/// session; dropping the future closes the upstream socket and both channels.
async fn relay(
    upstream: TcpStream,
    mut inbound_rx: mpsc::Receiver<Bytes>,
    buffered_payload: Vec<u8>,
    outbound_tx: mpsc::Sender<io::Result<Bytes>>,
) -> io::Result<()> {
    let started = Instant::now();
    // Milliseconds after `started` of the last traffic. Ordering: the value is
    // only a deadline hint and publishes no other memory.
    let last_activity_ms = AtomicU64::new(0);
    let touch = || {
        let elapsed = u64::try_from(started.elapsed().as_millis()).unwrap_or(u64::MAX);
        last_activity_ms.store(elapsed, Ordering::Relaxed);
    };
    let (mut upstream_reader, mut upstream_writer) = upstream.into_split();

    let upload = async {
        if !buffered_payload.is_empty() {
            upstream_writer.write_all(&buffered_payload).await?;
        }
        while let Some(chunk) = inbound_rx.recv().await {
            touch();
            upstream_writer.write_all(&chunk).await?;
        }
        upstream_writer.shutdown().await
    };
    let download = async {
        let mut buffer = vec![0u8; STREAM_BUFFER_SIZE];
        loop {
            let read = tokio::select! {
                read = upstream_reader.read(&mut buffer) => read?,
                () = outbound_tx.closed() => return Ok(()),
            };
            if read == 0 {
                return Ok(());
            }
            touch();
            if outbound_tx.send(Ok(Bytes::copy_from_slice(&buffer[..read]))).await.is_err() {
                return Ok(());
            }
        }
    };
    let idle = async {
        loop {
            let last = Duration::from_millis(last_activity_ms.load(Ordering::Relaxed));
            let deadline = started + last + SESSION_IDLE_TIMEOUT;
            if Instant::now() >= deadline {
                return;
            }
            sleep_until(deadline).await;
        }
    };
    tokio::pin!(upload, download, idle);

    let mut uploading = true;
    loop {
        tokio::select! {
            result = &mut upload, if uploading => {
                result?;
                uploading = false;
            }
            result = &mut download => return result,
            // An idle end is a normal close, not a failure to report.
            () = &mut idle => return Ok(()),
        }
    }
}

fn redact_upstream_connect_error(error: io::Error) -> io::Error {
    io::Error::new(error.kind(), "upstream connect failed")
}

/// # Cancel safety
///
/// NOT cancel-safe: a timeout drops any partial header already consumed from the
/// channel; the whole unauthenticated session is discarded on expiry.
async fn read_request_header_with_timeout(
    inbound_rx: &mut mpsc::Receiver<Bytes>,
    deadline: Duration,
) -> io::Result<(ripdpi_vless::wire::DecodedRequestHeader, Vec<u8>)> {
    timeout(deadline, read_request_header(inbound_rx))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "VLESS request header timed out"))?
}

async fn read_request_header(
    inbound_rx: &mut mpsc::Receiver<Bytes>,
) -> io::Result<(ripdpi_vless::wire::DecodedRequestHeader, Vec<u8>)> {
    let mut buffered = Vec::new();
    loop {
        match ripdpi_vless::wire::parse_request_header(&buffered) {
            Ok(decoded) => {
                let remaining = buffered.split_off(decoded.consumed_len);
                return Ok((decoded, remaining));
            }

            Err(ripdpi_vless::wire::ParseRequestError::NeedMoreData) => {
                let Some(chunk) = inbound_rx.recv().await else {
                    return Err(io::Error::new(
                        io::ErrorKind::UnexpectedEof,
                        "xHTTP POST stream ended before the VLESS request header completed",
                    ));
                };
                buffered.extend_from_slice(&chunk);
            }

            Err(error) => return Err(io::Error::new(io::ErrorKind::InvalidData, error)),
        }
    }
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use bytes::Bytes;
    use tokio::net::TcpListener;
    use tokio::sync::mpsc;

    use super::{read_request_header_with_timeout, redact_upstream_connect_error, run_session};

    /// Runs a session whose POST channel already holds a VLESS header for
    /// `target` and stays open. The returned receiver is the GET stream.
    fn start_session(
        target: &str,
    ) -> (impl Future<Output = std::io::Result<()>> + use<>, mpsc::Sender<Bytes>, mpsc::Receiver<std::io::Result<Bytes>>)
    {
        let (inbound_tx, inbound_rx) = mpsc::channel(8);
        let (outbound_tx, outbound_rx) = mpsc::channel(8);
        let header = ripdpi_vless::wire::encode_request(&[7; 16], &[], target).expect("VLESS header");
        inbound_tx.try_send(Bytes::from(header)).expect("queue header");
        (run_session(inbound_rx, outbound_tx, [7; 16], None, || true), inbound_tx, outbound_rx)
    }

    #[test]
    fn upstream_connect_error_does_not_export_destination_or_provider_text() {
        let error = std::io::Error::new(
            std::io::ErrorKind::ConnectionRefused,
            "connect private.example:443 with credential-shaped-provider-text",
        );

        let redacted = redact_upstream_connect_error(error);

        assert_eq!(redacted.kind(), std::io::ErrorKind::ConnectionRefused);
        assert_eq!(redacted.to_string(), "upstream connect failed");
    }
    #[tokio::test]
    /// # Cancel safety
    ///
    /// cancel-safe: the test owns only an in-memory channel.
    async fn incomplete_vless_header_times_out_without_closing_post_body() {
        let (_sender, mut receiver) = tokio::sync::mpsc::channel(1);
        let error = read_request_header_with_timeout(&mut receiver, std::time::Duration::from_millis(10))
            .await
            .expect_err("incomplete header must have a deadline");
        assert_eq!(error.kind(), std::io::ErrorKind::TimedOut);
    }

    #[tokio::test(flavor = "current_thread")]
    async fn silent_upstream_session_ends_at_the_idle_deadline() {
        let upstream = TcpListener::bind("127.0.0.1:0").await.expect("bind upstream");
        let target = upstream.local_addr().expect("upstream address").to_string();
        let (session, _post, _get) = start_session(&target);
        let session = tokio::spawn(session);

        // The upstream accepts, then never sends or closes; the POST stays open.
        let (_upstream_conn, _) = tokio::time::timeout(Duration::from_secs(5), upstream.accept())
            .await
            .expect("upstream dial")
            .expect("accept upstream");
        // Freeze the clock only after the real connect, so it cannot run ahead of it.
        tokio::time::pause();
        tokio::time::timeout(Duration::from_secs(3600), session)
            .await
            .expect("an idle session must end at its own deadline")
            .expect("session task")
            .expect("an idle end is a normal close");
    }
}
