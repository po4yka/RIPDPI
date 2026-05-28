use reqwest::header::{ACCEPT, CONTENT_TYPE};
use url::Url;

use crate::transport::DNS_MESSAGE_MEDIA_TYPE;
use crate::types::EncryptedDnsError;

pub(super) fn build_doh_http1_request(url: &Url, query_len: usize) -> Result<String, EncryptedDnsError> {
    build_post_http1_request(url, query_len, DNS_MESSAGE_MEDIA_TYPE, DNS_MESSAGE_MEDIA_TYPE)
}

pub(super) fn build_post_http1_request(
    url: &Url,
    body_len: usize,
    content_type: &str,
    accept: &str,
) -> Result<String, EncryptedDnsError> {
    let request_target = doh_request_target(url);
    let host_header = doh_host_header(url)?;
    Ok(format!(
        "POST {request_target} HTTP/1.1\r\nHost: {host_header}\r\n{}: {content_type}\r\n{}: {accept}\r\nContent-Length: {body_len}\r\nConnection: close\r\n\r\n",
        CONTENT_TYPE.as_str(),
        ACCEPT.as_str(),
    ))
}

fn doh_request_target(url: &Url) -> String {
    let mut target = if url.path().is_empty() { "/".to_string() } else { url.path().to_string() };
    if let Some(query) = url.query() {
        target.push('?');
        target.push_str(query);
    }
    target
}

fn doh_host_header(url: &Url) -> Result<String, EncryptedDnsError> {
    let host = url.host_str().ok_or(EncryptedDnsError::MissingHost)?;
    let host_header = match url.port() {
        Some(port) if Some(port) != url.port_or_known_default() => format!("{host}:{port}"),
        _ => host.to_string(),
    };
    Ok(host_header)
}
