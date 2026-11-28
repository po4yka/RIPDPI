use std::sync::Arc;
use std::time::Duration;

use rustls::ClientConfig;
use rustls::pki_types::ServerName;
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::time::timeout;
use tokio_rustls::TlsConnector;
use tokio_rustls::client::TlsStream;
use url::Url;

use super::http1_request::build_post_http1_request;
use super::http1_response::read_doh_response;
use crate::resolver::EncryptedDnsResolver;
use crate::transport::tls_version_refused;
use crate::types::{BoxedDnsTcpStream, EncryptedDnsError, EncryptedDnsTransport};

/// # Cancel safety:
/// cancel-safe: cancellation drops the owned connection and all exchange state.
pub(super) async fn exchange_doh_with_connector(
    resolver: &EncryptedDnsResolver,
    query_bytes: &[u8],
) -> Result<Vec<u8>, EncryptedDnsError> {
    let base_url = resolver.inner.endpoint.doh_url.as_ref().ok_or(EncryptedDnsError::MissingDohUrl)?;
    exchange_binary_post_with_connector(
        resolver,
        base_url,
        query_bytes,
        crate::transport::DNS_MESSAGE_MEDIA_TYPE,
        crate::transport::DNS_MESSAGE_MEDIA_TYPE,
    )
    .await
}

/// # Cancel safety:
/// cancel-safe: cancellation drops the owned TCP or TLS connection.
pub(super) async fn exchange_binary_post_with_connector(
    resolver: &EncryptedDnsResolver,
    base_url: &str,
    body: &[u8],
    content_type: &str,
    accept: &str,
) -> Result<Vec<u8>, EncryptedDnsError> {
    let url = Url::parse(base_url).map_err(|err| EncryptedDnsError::InvalidUrl(err.to_string()))?;
    if !url.scheme().eq_ignore_ascii_case("https") {
        let mut tcp_stream = resolver.connect_doh_tcp().await?;
        return exchange_binary_post_over_stream(
            &mut tcp_stream,
            &url,
            body,
            content_type,
            accept,
            resolver.inner.timeout,
        )
        .await;
    }
    if matches!(resolver.inner.transport, EncryptedDnsTransport::Socks5Unix { .. }) {
        return super::h2_exchange::exchange_binary_post_h2(resolver, &url, body, content_type, accept).await;
    }
    let mut tls_stream = connect_doh_tls(resolver, &url).await?;
    exchange_binary_post_over_stream(&mut tls_stream, &url, body, content_type, accept, resolver.inner.timeout).await
}

/// # Cancel safety:
/// cancel-safe: cancellation drops the owned TCP or TLS connection.
pub(super) async fn connect_doh_tls(
    resolver: &EncryptedDnsResolver,
    url: &Url,
) -> Result<TlsStream<BoxedDnsTcpStream>, EncryptedDnsError> {
    let tcp_stream = resolver.connect_doh_tcp().await?;
    // RFC 8484 section 3 / RFC 2818: the DoH URI host is the TLS identity.
    let tls_name = match url.host() {
        Some(url::Host::Domain(name)) => name.to_string(),
        Some(url::Host::Ipv4(ip)) => ip.to_string(),
        Some(url::Host::Ipv6(ip)) => ip.to_string(),
        None => return Err(EncryptedDnsError::MissingHost),
    };
    let server_name = ServerName::try_from(tls_name.clone()).map_err(|err| EncryptedDnsError::Tls(err.to_string()))?;
    let handshake = |config: &Arc<ClientConfig>, tcp_stream| {
        timeout(resolver.inner.timeout, TlsConnector::from(config.clone()).connect(server_name.clone(), tcp_stream))
    };
    let attempt = match handshake(&resolver.inner.manual_doh_tls_config, tcp_stream).await {
        // GREASE ECH limits the handshake to TLS 1.3. A TLS 1.2-only server
        // refuses it; retry once without GREASE. SNI is sent both times.
        Ok(Err(err)) if tls_version_refused(&err) => {
            handshake(&resolver.inner.manual_doh_fallback_tls_config, resolver.connect_doh_tcp().await?).await
        }
        attempt => attempt,
    };
    match attempt {
        Ok(Ok(stream)) => Ok(stream),
        Ok(Err(err)) => Err(EncryptedDnsError::Tls(format!("DoH TLS handshake to {tls_name}: {err}"))),
        Err(_) => Err(EncryptedDnsError::Tls(format!("DoH TLS handshake to {tls_name} timed out"))),
    }
}

/// # Cancel safety:
/// NOT cancel-safe: HTTP bytes can be consumed or sent. The caller must discard the stream.
pub(super) async fn exchange_binary_post_over_stream<S>(
    stream: &mut S,
    url: &Url,
    body: &[u8],
    content_type: &str,
    accept: &str,
    deadline: Duration,
) -> Result<Vec<u8>, EncryptedDnsError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let request = build_post_http1_request(url, body, content_type, accept)?;
    timeout(deadline, async {
        let (mut sender, connection) = hyper::client::conn::http1::Builder::new()
            .max_buf_size(super::MAX_DOH_HEADER_BYTES)
            .handshake(hyper_util::rt::TokioIo::new(stream))
            .await
            .map_err(http_error)?;
        let exchange = async {
            let response = sender.send_request(request).await.map_err(http_error)?;
            read_doh_response(response, accept).await
        };
        tokio::pin!(connection, exchange);
        tokio::select! {
            result = &mut exchange => result,
            result = &mut connection => {
                result.map_err(http_error)?;
                exchange.await
            }
        }
    })
    .await
    .map_err(|_| EncryptedDnsError::Request("DoH exchange timed out".into()))?
}

pub(super) fn http_error(error: hyper::Error) -> EncryptedDnsError {
    EncryptedDnsError::Request(format!("DoH HTTP exchange: {error}"))
}
