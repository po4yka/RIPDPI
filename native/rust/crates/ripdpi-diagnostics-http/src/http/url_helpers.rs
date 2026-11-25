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

pub struct HttpsEndpoint {
    pub host: String,
    pub port: u16,
    pub authority: String,
    pub request_target: String,
}

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

pub fn build_doh_json_query_url(endpoint: &str, query_host: &str) -> Result<String, &'static str> {
    let name = ripdpi_diagnostics_contracts::util::encode_doh_json_query_name(query_host)
        .ok_or("invalid DNS JSON query name")?;
    if endpoint.contains('#') {
        return Err("DNS JSON endpoint must not contain a fragment");
    }
    let uri = endpoint.parse::<http::Uri>().map_err(|_| "invalid DNS JSON endpoint")?;
    let authority = uri.authority().ok_or("DNS JSON endpoint requires an authority")?;
    if authority.as_str().contains('@') || authority.host().is_empty() {
        return Err("DNS JSON endpoint must not contain user information");
    }
    let host = authority.host();
    let literal = host.strip_prefix('[').and_then(|host| host.strip_suffix(']')).unwrap_or(host);
    let loopback = literal.parse::<std::net::IpAddr>().is_ok_and(|ip| ip.is_loopback());
    match uri.scheme_str() {
        Some(scheme) if scheme.eq_ignore_ascii_case("https") => {}
        Some(scheme) if scheme.eq_ignore_ascii_case("http") && loopback => {}
        _ => return Err("DNS JSON endpoint requires HTTPS"),
    }
    let checked_endpoint = format!("https://{authority}{}", uri.path_and_query().map_or("/", |target| target.as_str()));
    parse_https_endpoint(&checked_endpoint).map_err(|_| "invalid DNS JSON endpoint")?;
    let mut has_content_type = false;
    if let Some(query) = uri.query() {
        for field in query.split('&').filter(|field| !field.is_empty()) {
            let (key, value) = field.split_once('=').unwrap_or((field, ""));
            let key = decode_query_component(key)?;
            let value = decode_query_component(value)?;
            match key.as_slice() {
                b"name" | b"type" => return Err("DNS JSON endpoint already specifies a query"),
                b"ct" => {
                    if has_content_type
                        || !matches!(value.as_slice(), b"application/dns-json" | b"application/x-javascript")
                    {
                        return Err("conflicting DNS JSON content type");
                    }
                    has_content_type = true;
                }
                _ => {}
            }
        }
    }
    let separator = if uri.query().is_none() {
        "?"
    } else if endpoint.ends_with(['?', '&']) {
        ""
    } else {
        "&"
    };
    let mut url = format!("{endpoint}{separator}name={name}&type=A");
    if !has_content_type {
        url.push_str("&ct=application/dns-json");
    }
    Ok(url)
}

fn decode_query_component(value: &str) -> Result<Vec<u8>, &'static str> {
    let mut decoded = Vec::with_capacity(value.len());
    let mut bytes = value.bytes();
    while let Some(byte) = bytes.next() {
        decoded.push(match byte {
            b'%' => {
                let high = bytes.next().and_then(|b| char::from(b).to_digit(16));
                let low = bytes.next().and_then(|b| char::from(b).to_digit(16));
                let (Some(high), Some(low)) = (high, low) else {
                    return Err("invalid percent escape in DNS JSON endpoint");
                };
                (high * 16 + low) as u8
            }
            b'+' => b' ',
            _ => byte,
        });
    }
    Ok(decoded)
}

#[cfg(test)]
mod doh_query_url_tests {
    use super::build_doh_json_query_url;
    #[test]
    fn preserves_endpoint_query_and_encodes_one_query_name() {
        assert_eq!(
            build_doh_json_query_url(
                "https://dns.example/resolve?trace=a%2Bb&ct=application%2Fdns-json",
                "a&b.example"
            ),
            Ok("https://dns.example/resolve?trace=a%2Bb&ct=application%2Fdns-json&name=a%26b.example&type=A".into())
        );
        for endpoint in ["https://dns.example/resolve", "https://dns.example/resolve?"] {
            assert_eq!(
                build_doh_json_query_url(endpoint, "selected.example"),
                Ok("https://dns.example/resolve?name=selected.example&type=A&ct=application/dns-json".into())
            );
        }
    }
    #[test]
    fn rejects_conflicting_parameters_and_unsupported_endpoints() {
        for endpoint in [
            "https://dns.example/resolve?name=other.example",
            "https://dns.example/resolve?%6eame=other.example",
            "https://dns.example/resolve?type=AAAA",
            "https://dns.example/resolve?ct=application/dns-message",
            "https://dns.example/resolve?ct=application/dns-json&ct=application/dns-json",
            "https://dns.example/res%QZolve",
            "https://dns.example/resolve%",
            "https://dns.example:0/resolve",
            "https://dns.example:65536/resolve",
            "https://dns.example:bad/resolve",
            "http://127.0.0.1:1234/res%QZolve",
            "http://127.0.0.1:0/resolve",
            "http://127.0.0.1:65536/resolve",
            "http://[::1]:bad/resolve",
            "https://dns.example/resolve?trace=%QZ",
            "https://dns.example/resolve?trace=%",
            "https://dns.example/resolve#fragment",
            "https://user@dns.example/resolve",
            "http://dns.example/resolve",
            "file:///resolve",
            "/resolve",
        ] {
            assert!(build_doh_json_query_url(endpoint, "selected.example").is_err(), "{endpoint}");
        }
        assert!(build_doh_json_query_url("https://dns.example/resolve", "").is_err());
    }
    #[test]
    fn permits_only_literal_loopback_http_for_local_fixtures() {
        for endpoint in ["http://127.0.0.1:1234/resolve", "http://[::1]:1234/resolve"] {
            assert!(build_doh_json_query_url(endpoint, "selected.example").is_ok());
        }
        assert!(build_doh_json_query_url("http://localhost:1234/resolve", "selected.example").is_err());
    }
}
