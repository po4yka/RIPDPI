pub fn extract_host_from_url(url: &str) -> Option<String> {
    let without_scheme = url.strip_prefix("https://").or_else(|| url.strip_prefix("http://"))?;
    let host = without_scheme.split('/').next()?;
    let host = host.split(':').next()?;
    if host.is_empty() { None } else { Some(host.to_string()) }
}

pub fn extract_path_from_url(url: &str) -> String {
    let without_scheme = url.strip_prefix("https://").or_else(|| url.strip_prefix("http://")).unwrap_or(url);
    match without_scheme.find('/') {
        Some(idx) => without_scheme[idx..].to_string(),
        None => "/".to_string(),
    }
}

/// A validated HTTPS authority and the origin-form request target to send to it.
pub struct HttpsEndpoint {
    pub host: String,
    pub port: u16,
    pub authority: String,
    pub request_target: String,
}

/// Parse one HTTPS endpoint without credentials or a fragment.
///
/// # Errors
///
/// Returns an error for a malformed URI, authority, port, or percent escape.
pub fn parse_https_endpoint(url: &str) -> Result<HttpsEndpoint, &'static str> {
    let invalid = "invalid_https_endpoint";
    if url.contains('#') || !url.is_ascii() {
        return Err(invalid);
    }
    for (position, byte) in url.bytes().enumerate() {
        if byte == b'%'
            && !url.as_bytes().get(position + 1..position + 3).is_some_and(|s| s.iter().all(u8::is_ascii_hexdigit))
        {
            return Err(invalid);
        }
    }
    let uri = url.parse::<http::Uri>().map_err(|_| invalid)?;
    if uri.scheme_str() != Some("https") {
        return Err(invalid);
    }
    let authority = uri.authority().ok_or(invalid)?.as_str();
    if authority.contains('@') {
        return Err(invalid);
    }
    let (host, suffix) = if let Some(bracketed) = authority.strip_prefix('[') {
        let (host, suffix) = bracketed.split_once(']').ok_or(invalid)?;
        let _ = host.parse::<std::net::Ipv6Addr>().map_err(|_| invalid)?;
        (host, suffix)
    } else {
        authority.find(':').map_or((authority, ""), |split| authority.split_at(split))
    };
    let port = if suffix.is_empty() {
        443
    } else {
        let value =
            suffix.strip_prefix(':').filter(|value| value.bytes().all(|b| b.is_ascii_digit())).ok_or(invalid)?;
        value.parse::<u16>().ok().filter(|port| *port > 0).ok_or(invalid)?
    };
    let target = crate::transport::TargetAddress::Host(host.to_string());
    crate::tls::make_server_name(host, &target).map_err(|_| invalid)?;
    let request_target = match uri.query() {
        Some(query) => format!("{}?{query}", uri.path()),
        None => uri.path().to_string(),
    };
    Ok(HttpsEndpoint { host: host.to_owned(), port, authority: authority.to_string(), request_target })
}

#[cfg(test)]
mod tests {
    use super::parse_https_endpoint;

    #[test]
    fn parses_https_dial_address_and_origin_form() {
        for (url, host, port, authority, request_target) in [
            ("https://example.test", "example.test", 443, "example.test", "/"),
            ("https://example.test?test=%20", "example.test", 443, "example.test", "/?test=%20"),
            ("https://[2001:db8::1]:8443/media", "2001:db8::1", 8443, "[2001:db8::1]:8443", "/media"),
        ] {
            let actual = parse_https_endpoint(url).expect(url);
            assert_eq!(actual.host, host);
            assert_eq!(actual.port, port);
            assert_eq!(actual.authority, authority);
            assert_eq!(actual.request_target, request_target);
        }
    }

    #[test]
    fn rejects_unsafe_or_malformed_https_endpoints() {
        for url in [
            "http://example.test/",
            "https:///path",
            "https://user@example.test/",
            "https://example.test:0/",
            "https://example.test:65536/",
            "https://example.test:/",
            "https://example.test:bad/",
            "https://example.test:+443/",
            "https://[example.test]/",
            "https://[192.0.2.1]:443/",
            "https://[2001:db8::1]x:8443/",
            "https://[2001:db8::1]:+8443/",
            "https://[2001:db8::1]:443x/",
            "https://[2001:db8::1]:8443:443/",
            "https://example.test/?bad=%XX",
            "https://example.test/path#fragment",
            "https://example.test/ HTTP/1.1\r\nInjected: true",
        ] {
            assert!(parse_https_endpoint(url).is_err(), "{url:?}");
        }
    }
}
