use std::sync::Arc;

use rustls::ClientConfig;
use rustls::pki_types::ServerName;
use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt};
use tokio::time::timeout;
use tokio_rustls::TlsConnector;
use url::Url;

use super::http1_request::{build_doh_http1_request, build_post_http1_request};
use super::http1_response::read_doh_response;
use crate::resolver::EncryptedDnsResolver;
use crate::types::EncryptedDnsError;

pub(super) async fn exchange_doh_manually(
    resolver: &EncryptedDnsResolver,
    query_bytes: &[u8],
) -> Result<Vec<u8>, EncryptedDnsError> {
    let base_url = resolver.inner.endpoint.doh_url.as_ref().ok_or(EncryptedDnsError::MissingDohUrl)?;
    exchange_binary_post_manually(
        resolver,
        base_url,
        query_bytes,
        crate::transport::DNS_MESSAGE_MEDIA_TYPE,
        crate::transport::DNS_MESSAGE_MEDIA_TYPE,
    )
    .await
}

pub(super) async fn exchange_binary_post_manually(
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
        exchange_binary_post_over_stream(resolver, &mut tls_stream, &url, body, content_type, accept).await
    } else {
        exchange_binary_post_over_stream(resolver, &mut tcp_stream, &url, body, content_type, accept).await
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

async fn exchange_binary_post_over_stream<S>(
    resolver: &EncryptedDnsResolver,
    stream: &mut S,
    url: &Url,
    body: &[u8],
    content_type: &str,
    accept: &str,
) -> Result<Vec<u8>, EncryptedDnsError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let request = if content_type == crate::transport::DNS_MESSAGE_MEDIA_TYPE
        && accept == crate::transport::DNS_MESSAGE_MEDIA_TYPE
    {
        build_doh_http1_request(url, body.len())?
    } else {
        build_post_http1_request(url, body.len(), content_type, accept)?
    };
    let mut response = Vec::new();
    match timeout(resolver.inner.timeout, async {
        stream
            .write_all(request.as_bytes())
            .await
            .map_err(|err| EncryptedDnsError::Request(format!("DoH write request headers: {err}")))?;
        stream
            .write_all(body)
            .await
            .map_err(|err| EncryptedDnsError::Request(format!("DoH write request body: {err}")))?;
        stream.flush().await.map_err(|err| EncryptedDnsError::Request(format!("DoH flush stream: {err}")))?;
        response = read_doh_response(stream).await?;
        Ok::<(), EncryptedDnsError>(())
    })
    .await
    {
        Ok(result) => result?,
        Err(_) => return Err(EncryptedDnsError::Request("DoH exchange timed out".to_string())),
    }

    Ok(response)
}
