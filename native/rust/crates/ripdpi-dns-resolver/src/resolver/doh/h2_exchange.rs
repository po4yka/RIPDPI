use std::future::Future;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{MutexGuard, PoisonError};

use bytes::Bytes;
use http_body_util::Full;
use hyper::Request;
use hyper::client::conn::http2::SendRequest;
use hyper_util::rt::{TokioExecutor, TokioIo, TokioTimer};
use reqwest::header::{ACCEPT, CONTENT_TYPE};
use tokio::time::{Instant, timeout_at};
use url::{Position, Url};

use super::http1_exchange::{connect_doh_tls, exchange_binary_post_over_stream, http_error};
use super::http1_response::read_doh_response;
use crate::resolver::EncryptedDnsResolver;
use crate::transport::{DOH_H2_KEEP_ALIVE_INTERVAL, DOH_H2_KEEP_ALIVE_TIMEOUT};
use crate::types::EncryptedDnsError;

static NEXT_CONNECTION_ID: AtomicU64 = AtomicU64::new(0);

#[derive(Clone, Debug)]
pub(in crate::resolver) struct DohH2Connection {
    id: u64,
    authority: String,
    runtime: tokio::runtime::Id,
    sender: SendRequest<Full<Bytes>>,
}

struct DohTarget {
    authority: String,
    uri: String,
}

enum Failure {
    Send(EncryptedDnsError),
    Other(EncryptedDnsError),
}

/// # Cancel safety:
/// cancel-safe: cancellation drops the request; the shared HTTP/2 connection stays valid, and a new one is
/// stored only after its handshake completes.
pub(super) async fn exchange_binary_post_h2(
    resolver: &EncryptedDnsResolver,
    url: &Url,
    body: &[u8],
    content_type: &str,
    accept: &str,
) -> Result<Vec<u8>, EncryptedDnsError> {
    let target = DohTarget::new(url)?;
    let total = resolver.inner.timeout;
    let deadline = Instant::now() + total;
    if let Some(connection) = usable_connection(resolver, &target) {
        let cached_deadline = deadline.min(Instant::now() + total / 2);
        match within(cached_deadline, exchange_on(&connection, &target, body, content_type, accept)).await {
            Ok(reply) => return Ok(reply),
            Err(Failure::Other(error)) => {
                forget_connection(resolver, connection.id);
                return Err(error);
            }
            Err(Failure::Send(_)) => forget_connection(resolver, connection.id),
        }
    }
    let opened = within_error(deadline, open_connection(resolver, url, &target)).await?;
    match opened {
        Opened::Http1(mut stream) => {
            exchange_binary_post_over_stream(&mut stream, url, body, content_type, accept, total).await
        }
        Opened::H2(connection) => {
            match within(deadline, exchange_on(&connection, &target, body, content_type, accept)).await {
                Ok(reply) => Ok(reply),
                Err(Failure::Send(error) | Failure::Other(error)) => {
                    forget_connection(resolver, connection.id);
                    Err(error)
                }
            }
        }
    }
}

enum Opened {
    H2(DohH2Connection),
    Http1(Box<tokio_rustls::client::TlsStream<crate::types::BoxedDnsTcpStream>>),
}

/// # Cancel safety:
/// cancel-safe: the connect lock is released on cancellation, and a connection is stored only after its
/// handshake completes.
async fn open_connection(
    resolver: &EncryptedDnsResolver,
    url: &Url,
    target: &DohTarget,
) -> Result<Opened, EncryptedDnsError> {
    let _connecting = resolver.inner.doh_h2_connect.lock().await;
    if let Some(connection) = usable_connection(resolver, target) {
        return Ok(Opened::H2(connection));
    }
    let tls_stream = connect_doh_tls(resolver, url).await?;
    if tls_stream.get_ref().1.alpn_protocol() != Some(b"h2".as_slice()) {
        return Ok(Opened::Http1(Box::new(tls_stream)));
    }
    let (sender, connection) = hyper::client::conn::http2::Builder::new(TokioExecutor::new())
        .timer(TokioTimer::new())
        .keep_alive_interval(DOH_H2_KEEP_ALIVE_INTERVAL)
        .keep_alive_timeout(DOH_H2_KEEP_ALIVE_TIMEOUT)
        .keep_alive_while_idle(true)
        .handshake(TokioIo::new(tls_stream))
        .await
        .map_err(http_error)?;
    tokio::spawn(connection);
    let opened = DohH2Connection {
        id: NEXT_CONNECTION_ID.fetch_add(1, Ordering::Relaxed),
        authority: target.authority.clone(),
        runtime: tokio::runtime::Handle::current().id(),
        sender,
    };
    *lock_slot(resolver) = Some(opened.clone());
    Ok(Opened::H2(opened))
}

fn lock_slot(resolver: &EncryptedDnsResolver) -> MutexGuard<'_, Option<DohH2Connection>> {
    resolver.inner.doh_h2.lock().unwrap_or_else(PoisonError::into_inner)
}

fn usable_connection(resolver: &EncryptedDnsResolver, target: &DohTarget) -> Option<DohH2Connection> {
    let mut slot = lock_slot(resolver);
    if slot.as_ref().is_some_and(|connection| connection.sender.is_closed()) {
        *slot = None;
    }
    let runtime = tokio::runtime::Handle::current().id();
    slot.as_ref()
        .filter(|connection| connection.authority == target.authority && connection.runtime == runtime)
        .cloned()
}

fn forget_connection(resolver: &EncryptedDnsResolver, id: u64) {
    let mut slot = lock_slot(resolver);
    if slot.as_ref().is_some_and(|connection| connection.id == id) {
        *slot = None;
    }
}

/// # Cancel safety:
/// cancel-safe: cancellation drops the request; the outcome on the server stays unknown.
async fn exchange_on(
    connection: &DohH2Connection,
    target: &DohTarget,
    body: &[u8],
    content_type: &str,
    accept: &str,
) -> Result<Vec<u8>, Failure> {
    let request = Request::post(&target.uri)
        .header(CONTENT_TYPE, content_type)
        .header(ACCEPT, accept)
        .body(Full::new(Bytes::copy_from_slice(body)))
        .map_err(|error| Failure::Other(EncryptedDnsError::Request(error.to_string())))?;
    let response =
        connection.sender.clone().send_request(request).await.map_err(|error| Failure::Send(http_error(error)))?;
    read_doh_response(response, accept).await.map_err(Failure::Other)
}

/// # Cancel safety:
/// cancel-safe: cancellation drops the inner future.
async fn within<T>(deadline: Instant, future: impl Future<Output = Result<T, Failure>>) -> Result<T, Failure> {
    timeout_at(deadline, future)
        .await
        .unwrap_or_else(|_| Err(Failure::Send(EncryptedDnsError::Request("DoH exchange timed out".into()))))
}

/// # Cancel safety:
/// cancel-safe: cancellation drops the inner future.
async fn within_error<T>(
    deadline: Instant,
    future: impl Future<Output = Result<T, EncryptedDnsError>>,
) -> Result<T, EncryptedDnsError> {
    timeout_at(deadline, future)
        .await
        .unwrap_or_else(|_| Err(EncryptedDnsError::Request("DoH exchange timed out".into())))
}

impl DohTarget {
    fn new(url: &Url) -> Result<Self, EncryptedDnsError> {
        if url.host_str().is_none() {
            return Err(EncryptedDnsError::MissingHost);
        }
        let authority = format!("{}://{}", url.scheme(), &url[Position::BeforeHost..Position::AfterPort]);
        let uri = format!("{authority}{}", &url[Position::BeforePath..Position::AfterQuery]);
        Ok(Self { authority, uri })
    }
}
