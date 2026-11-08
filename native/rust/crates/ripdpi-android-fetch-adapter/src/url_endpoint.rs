use std::io;

use url::{Host, Url};

pub(crate) struct UrlEndpoint {
    pub(crate) host: String,
    pub(crate) http_host: String,
    pub(crate) port: u16,
    pub(crate) target_path: String,
}

#[inline(never)]
pub(crate) fn parse_url_endpoint(url: &Url) -> io::Result<UrlEndpoint> {
    let url_host =
        url.host().ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "native TLS fetch URL has no host"))?;
    let http_host = url_host.to_string();
    let host = match url_host {
        Host::Domain(host) if url.scheme() == "https" => ripdpi_tls_profiles::normalize_server_name(host)
            .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, error))?,
        Host::Domain(host) => host.to_owned(),
        Host::Ipv4(ip) => ip.to_string(),
        Host::Ipv6(ip) => ip.to_string(),
    };
    let port = url.port_or_known_default().unwrap_or(default_port(url.scheme()));
    let path = url.path().to_string();
    let query_suffix = url.query().map(|query| format!("?{query}")).unwrap_or_default();
    Ok(UrlEndpoint { host, http_host, port, target_path: format!("{path}{query_suffix}") })
}

fn default_port(scheme: &str) -> u16 {
    match scheme {
        "http" => 80,
        _ => 443,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tls_identity_validation_does_not_restrict_cleartext_reg_names() {
        let endpoint = parse_url_endpoint(&Url::parse("http://_status.example./check").expect("HTTP URL"))
            .expect("cleartext reg-name stays supported");
        assert_eq!(endpoint.host, "_status.example.");
        assert!(parse_url_endpoint(&Url::parse("https://_status.example/check").expect("HTTPS URL")).is_err());
    }

    #[test]
    fn tls_identity_url_host_keeps_idna_and_removes_ip_authority_brackets() {
        for (url, expected) in [
            ("https://[::1]:8443/test", "::1"),
            ("https://bücher.test/test", "xn--bcher-kva.test"),
            ("https://example.test./test", "example.test"),
        ] {
            let endpoint = parse_url_endpoint(&Url::parse(url).expect("URL")).expect("endpoint");
            assert_eq!(endpoint.host, expected);
        }
    }

    #[test]
    fn http_request_preserves_url_authority_separately_from_tls_identity() {
        for (url, expected_name, expected_host) in [
            ("https://example.test.:8443/test", "example.test", "example.test.:8443"),
            ("https://[::1]:8443/test", "::1", "[::1]:8443"),
        ] {
            let endpoint = parse_url_endpoint(&Url::parse(url).expect("URL")).expect("endpoint");
            let request = crate::request_builder::build_request(
                &http::Method::GET,
                &endpoint.target_path,
                &endpoint.http_host,
                endpoint.port,
                true,
                &std::collections::BTreeMap::new(),
            )
            .expect("HTTP request");
            assert_eq!(endpoint.host, expected_name);
            assert_eq!(request.headers()[http::header::HOST], expected_host);
        }
    }
}
