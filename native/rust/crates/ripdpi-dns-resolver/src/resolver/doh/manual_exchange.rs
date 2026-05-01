use rustls::pki_types::ServerName;
use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt};
use tokio::time::timeout;
use tokio_rustls::TlsConnector;
use url::Url;

use super::http1_request::build_doh_http1_request;
use super::http1_response::read_doh_response;
use crate::resolver::EncryptedDnsResolver;
use crate::types::EncryptedDnsError;

pub(super) async fn exchange_doh_manually(
    resolver: &EncryptedDnsResolver,
    query_bytes: &[u8],
) -> Result<Vec<u8>, EncryptedDnsError> {
    let base_url = resolver.inner.endpoint.doh_url.as_ref().ok_or(EncryptedDnsError::MissingDohUrl)?;
    let url = Url::parse(base_url).map_err(|err| EncryptedDnsError::InvalidUrl(err.to_string()))?;
    let mut tcp_stream = resolver.connect_plain_tcp().await?;

    if url.scheme().eq_ignore_ascii_case("https") {
        let tls_name =
            resolver.inner.endpoint.tls_server_name.clone().unwrap_or_else(|| resolver.inner.endpoint.host.clone());
        let server_name =
            ServerName::try_from(tls_name.clone()).map_err(|err| EncryptedDnsError::Tls(err.to_string()))?;
        let connector = TlsConnector::from(resolver.inner.dot_tls_config.clone());
        let mut tls_stream = match timeout(resolver.inner.timeout, connector.connect(server_name, tcp_stream)).await {
            Ok(Ok(stream)) => stream,
            Ok(Err(err)) => return Err(EncryptedDnsError::Tls(format!("DoH TLS handshake to {tls_name}: {err}"))),
            Err(_) => return Err(EncryptedDnsError::Tls(format!("DoH TLS handshake to {tls_name} timed out"))),
        };
        exchange_doh_over_stream(resolver, &mut tls_stream, &url, query_bytes).await
    } else {
        exchange_doh_over_stream(resolver, &mut tcp_stream, &url, query_bytes).await
    }
}

async fn exchange_doh_over_stream<S>(
    resolver: &EncryptedDnsResolver,
    stream: &mut S,
    url: &Url,
    query_bytes: &[u8],
) -> Result<Vec<u8>, EncryptedDnsError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let request = build_doh_http1_request(url, query_bytes.len())?;
    let mut response = Vec::new();
    match timeout(resolver.inner.timeout, async {
        stream
            .write_all(request.as_bytes())
            .await
            .map_err(|err| EncryptedDnsError::Request(format!("DoH write request headers: {err}")))?;
        stream
            .write_all(query_bytes)
            .await
            .map_err(|err| EncryptedDnsError::Request(format!("DoH write query body: {err}")))?;
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
