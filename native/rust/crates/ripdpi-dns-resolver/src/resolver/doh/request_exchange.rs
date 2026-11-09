use reqwest::header::{ACCEPT, CONTENT_TYPE};

use super::{MAX_DOH_RESPONSE_BYTES, http1_exchange};
use crate::resolver::EncryptedDnsResolver;
use crate::transport::{DNS_MESSAGE_MEDIA_TYPE, format_error_chain, tls_version_refused};
use crate::types::EncryptedDnsError;

/// # Cancel safety:
/// cancel-safe: cancellation drops local request state; the remote outcome can remain unknown.
pub(super) async fn exchange_doh(
    resolver: &EncryptedDnsResolver,
    query_bytes: &[u8],
) -> Result<Vec<u8>, EncryptedDnsError> {
    let body = match &resolver.inner.doh_client {
        None => http1_exchange::exchange_doh_with_connector(resolver, query_bytes).await?,
        Some(client) => {
            let base_url = resolver.inner.endpoint.doh_url.as_ref().ok_or(EncryptedDnsError::MissingDohUrl)?;
            post_reqwest(resolver, client, base_url, query_bytes, DNS_MESSAGE_MEDIA_TYPE, DNS_MESSAGE_MEDIA_TYPE)
                .await?
        }
    };
    // RFC 8484 section 4.2: the body must answer this query.
    crate::transport::ensure_response_matches_query(query_bytes, &body)?;
    Ok(body)
}

/// # Cancel safety:
/// cancel-safe: cancellation drops local request state; the remote outcome can remain unknown.
pub(super) async fn exchange_binary_post(
    resolver: &EncryptedDnsResolver,
    url: &str,
    body: &[u8],
    content_type: &str,
    accept: &str,
) -> Result<Vec<u8>, EncryptedDnsError> {
    match &resolver.inner.doh_client {
        None => http1_exchange::exchange_binary_post_with_connector(resolver, url, body, content_type, accept).await,
        Some(client) => post_reqwest(resolver, client, url, body, content_type, accept).await,
    }
}

/// Sends one POST through reqwest. A server that refuses the TLS 1.3-only
/// (GREASE ECH) handshake gets one retry on the client that also allows TLS 1.2.
///
/// # Cancel safety:
/// cancel-safe: a drop at either `send().await` or in the body read discards the request;
/// hyper returns a connection to the pool only when idle. The TLS 1.2 retry runs only after a
/// handshake refusal, before request bytes were sent. A cancelled POST outcome stays unknown.
async fn post_reqwest(
    resolver: &EncryptedDnsResolver,
    client: &reqwest::Client,
    url: &str,
    body: &[u8],
    content_type: &str,
    accept: &str,
) -> Result<Vec<u8>, EncryptedDnsError> {
    let send = |client: &reqwest::Client| {
        client.post(url).header(CONTENT_TYPE, content_type).header(ACCEPT, accept).body(body.to_vec()).send()
    };
    let first = send(client).await;
    let result = match (first, &resolver.inner.doh_fallback_client) {
        (Err(err), Some(fallback)) if tls_version_refused(&err) => send(fallback).await,
        (result, _) => result,
    };
    let response = result.map_err(|err| EncryptedDnsError::Request(format_error_chain(&err)))?;

    if !response.status().is_success() {
        return Err(EncryptedDnsError::HttpStatus(response.status()));
    }

    read_limited_response_body(response, accept).await
}

/// # Cancel safety:
///
/// cancel-safe: the response and accumulated bytes are owned by this call and dropped on cancellation.
async fn read_limited_response_body(
    mut response: reqwest::Response,
    accept: &str,
) -> Result<Vec<u8>, EncryptedDnsError> {
    super::http1_response::validate_response_headers(response.headers())?;
    super::http1_response::validate_response_content_type(response.headers(), accept)?;
    if response.content_length().is_some_and(|length| length > MAX_DOH_RESPONSE_BYTES as u64) {
        return Err(EncryptedDnsError::Request("DoH response Content-Length exceeds maximum size".to_string()));
    }

    let mut body = Vec::new();
    while let Some(chunk) = response.chunk().await.map_err(|err| EncryptedDnsError::Request(err.to_string()))? {
        if chunk.len() > MAX_DOH_RESPONSE_BYTES - body.len() {
            return Err(EncryptedDnsError::Request("DoH response body exceeds maximum size".to_string()));
        }
        body.extend_from_slice(&chunk);
    }
    Ok(body)
}

#[cfg(test)]
mod tests {
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    use super::{MAX_DOH_RESPONSE_BYTES, read_limited_response_body};
    use crate::types::EncryptedDnsError;

    #[tokio::test]
    /// # Cancel safety:
    ///
    /// cancel-safe: the test owns the local listener and response; cancellation drops both.
    async fn reqwest_response_rejects_oversized_chunked_body() {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind local listener");
        let address = listener.local_addr().expect("listener address");
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.expect("accept request");
            let mut request = Vec::new();
            while !request.ends_with(b"\r\n\r\n") {
                request.push(stream.read_u8().await.expect("read request header"));
            }
            stream
                .write_all(
                    format!(
                        "HTTP/1.1 200 OK\r\nTransfer-Encoding: chunked\r\n\r\n{:x}\r\n",
                        MAX_DOH_RESPONSE_BYTES + 1
                    )
                    .as_bytes(),
                )
                .await
                .expect("send response header");
            stream.write_all(&vec![0; MAX_DOH_RESPONSE_BYTES + 1]).await.expect("send oversized body");
            let _ = stream.write_all(b"\r\n0\r\n\r\n").await;
        });

        let response = reqwest::Client::new()
            .get(format!("http://{address}/dns-query"))
            .send()
            .await
            .expect("local HTTP response");
        let error = read_limited_response_body(response, "application/dns-message")
            .await
            .expect_err("oversized body must fail");
        assert!(matches!(error, EncryptedDnsError::Request(message) if message.contains("exceeds maximum size")));
        server.await.expect("server task");
    }
}
