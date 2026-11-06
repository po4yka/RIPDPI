use std::sync::Arc;
use std::time::Duration;

use rustls::ClientConfig;
use rustls::pki_types::ServerName;
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::time::timeout;
use tokio_rustls::TlsConnector;
use url::Url;

use super::http1_request::build_post_http1_request;
use super::http1_response::read_doh_response;
use crate::resolver::EncryptedDnsResolver;
use crate::types::EncryptedDnsError;

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
    let mut tcp_stream = resolver.connect_doh_tcp().await?;

    if url.scheme().eq_ignore_ascii_case("https") {
        let tls_name =
            resolver.inner.endpoint.tls_server_name.clone().unwrap_or_else(|| resolver.inner.endpoint.host.clone());
        let server_name =
            ServerName::try_from(tls_name.clone()).map_err(|err| EncryptedDnsError::Tls(err.to_string()))?;
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
        let mut tls_stream = match attempt {
            Ok(Ok(stream)) => stream,
            Ok(Err(err)) => return Err(EncryptedDnsError::Tls(format!("DoH TLS handshake to {tls_name}: {err}"))),
            Err(_) => return Err(EncryptedDnsError::Tls(format!("DoH TLS handshake to {tls_name} timed out"))),
        };
        exchange_binary_post_over_stream(&mut tls_stream, &url, body, content_type, accept, resolver.inner.timeout)
            .await
    } else {
        exchange_binary_post_over_stream(&mut tcp_stream, &url, body, content_type, accept, resolver.inner.timeout)
            .await
    }
}

/// Whether a TLS handshake failed because the server and the TLS 1.3-only
/// client share no protocol version.
fn tls_version_refused(err: &std::io::Error) -> bool {
    matches!(
        err.get_ref().and_then(|inner| inner.downcast_ref::<rustls::Error>()),
        Some(
            rustls::Error::AlertReceived(rustls::AlertDescription::ProtocolVersion)
                | rustls::Error::PeerIncompatible(rustls::PeerIncompatible::ServerTlsVersionIsDisabledByOurConfig)
        )
    )
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
            read_doh_response(response).await
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

fn http_error(error: hyper::Error) -> EncryptedDnsError {
    EncryptedDnsError::Request(format!("DoH HTTP exchange: {error}"))
}
