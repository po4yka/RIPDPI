use std::io;

use url::Url;

pub(crate) struct UrlEndpoint {
    pub(crate) host: String,
    pub(crate) port: u16,
    pub(crate) target_path: String,
}

#[inline(never)]
pub(crate) fn parse_url_endpoint(url: &Url) -> io::Result<UrlEndpoint> {
    let host = url
        .host_str()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "native TLS fetch URL has no host"))?
        .to_string();
    let port = url.port_or_known_default().unwrap_or(default_port(url.scheme()));
    let path = url.path().to_string();
    let query_suffix = url.query().map(|query| format!("?{query}")).unwrap_or_default();
    Ok(UrlEndpoint { host, port, target_path: format!("{path}{query_suffix}") })
}

fn default_port(scheme: &str) -> u16 {
    match scheme {
        "http" => 80,
        _ => 443,
    }
}
