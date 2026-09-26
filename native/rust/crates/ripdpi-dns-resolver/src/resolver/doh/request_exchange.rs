use reqwest::header::{ACCEPT, CONTENT_TYPE};

use super::{MAX_DOH_RESPONSE_BYTES, manual_exchange};
use crate::resolver::EncryptedDnsResolver;
use crate::transport::{DNS_MESSAGE_MEDIA_TYPE, format_error_chain};
use crate::types::EncryptedDnsError;

pub(super) async fn exchange_doh(
    resolver: &EncryptedDnsResolver,
    query_bytes: &[u8],
) -> Result<Vec<u8>, EncryptedDnsError> {
    if resolver.uses_direct_tcp_connector() || resolver.requires_direct_tcp_connector() {
        return manual_exchange::exchange_doh_manually(resolver, query_bytes).await;
    }

    let client = resolver
        .inner
        .doh_client
        .as_ref()
        .ok_or_else(|| EncryptedDnsError::InvalidEndpoint("DoH client not initialized".to_string()))?;
    let base_url = resolver.inner.endpoint.doh_url.as_ref().ok_or(EncryptedDnsError::MissingDohUrl)?;

    let response = client
        .post(base_url)
        .header(CONTENT_TYPE, DNS_MESSAGE_MEDIA_TYPE)
        .header(ACCEPT, DNS_MESSAGE_MEDIA_TYPE)
        .body(query_bytes.to_vec())
        .send()
        .await
        .map_err(|err| EncryptedDnsError::Request(format_error_chain(&err)))?;

    if !response.status().is_success() {
        return Err(EncryptedDnsError::HttpStatus(response.status()));
    }

    read_limited_response_body(response).await
}

pub(super) async fn exchange_binary_post(
    resolver: &EncryptedDnsResolver,
    url: &str,
    body: &[u8],
    content_type: &str,
    accept: &str,
) -> Result<Vec<u8>, EncryptedDnsError> {
    if resolver.uses_direct_tcp_connector() || resolver.requires_direct_tcp_connector() {
        return manual_exchange::exchange_binary_post_manually(resolver, url, body, content_type, accept).await;
    }

    let client = resolver
        .inner
        .doh_client
        .as_ref()
        .ok_or_else(|| EncryptedDnsError::InvalidEndpoint("DoH client not initialized".to_string()))?;

    let response = client
        .post(url)
        .header(CONTENT_TYPE, content_type)
        .header(ACCEPT, accept)
        .body(body.to_vec())
        .send()
        .await
        .map_err(|err| EncryptedDnsError::Request(format_error_chain(&err)))?;

    if !response.status().is_success() {
        return Err(EncryptedDnsError::HttpStatus(response.status()));
    }

    read_limited_response_body(response).await
}

// cancel-safe: the response and accumulated bytes are owned by this call and dropped on cancellation.
async fn read_limited_response_body(mut response: reqwest::Response) -> Result<Vec<u8>, EncryptedDnsError> {
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
    use tokio::io::AsyncWriteExt;
    use tokio::net::TcpListener;

    use super::{MAX_DOH_RESPONSE_BYTES, read_limited_response_body};
    use crate::types::EncryptedDnsError;

    #[tokio::test]
    // cancel-safe: the test owns the local listener and response; cancellation drops both.
    async fn reqwest_response_rejects_oversized_chunked_body() {
        let listener = TcpListener::bind("127.0.0.1:0").await.expect("bind local listener");
        let address = listener.local_addr().expect("listener address");
        let server = tokio::spawn(async move {
            let (mut stream, _) = listener.accept().await.expect("accept request");
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
        let error = read_limited_response_body(response).await.expect_err("oversized body must fail");
        assert!(matches!(error, EncryptedDnsError::Request(message) if message.contains("exceeds maximum size")));
        server.await.expect("server task");
    }
}
