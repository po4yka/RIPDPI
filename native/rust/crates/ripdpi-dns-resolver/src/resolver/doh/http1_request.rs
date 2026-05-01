use reqwest::header::{ACCEPT, CONTENT_TYPE};
use url::Url;

use crate::transport::DNS_MESSAGE_MEDIA_TYPE;
use crate::types::EncryptedDnsError;

pub(super) fn build_doh_http1_request(url: &Url, query_len: usize) -> Result<String, EncryptedDnsError> {
    let request_target = doh_request_target(url);
    let host_header = doh_host_header(url)?;
    Ok(format!(
        "POST {request_target} HTTP/1.1\r\nHost: {host_header}\r\n{}: {}\r\n{}: {}\r\nContent-Length: {query_len}\r\nConnection: close\r\n\r\n",
        CONTENT_TYPE.as_str(),
        DNS_MESSAGE_MEDIA_TYPE,
        ACCEPT.as_str(),
        DNS_MESSAGE_MEDIA_TYPE,
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
