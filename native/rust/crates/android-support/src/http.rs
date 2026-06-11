/// Build the value for the HTTP `Host` header from `host` and `port`,
/// omitting the port when it is the scheme's default (RFC 9110 §7.2).
///
/// # Arguments
///
/// * `host`  – hostname or IP-literal (no brackets needed for IPv6 — the
///   caller is responsible for supplying an already-bracketed address when
///   required).
/// * `port`  – TCP port the request is directed to.
/// * `https` – `true` when the request is carried over TLS (HTTPS default
///   port is 443); `false` for plain HTTP (default port is 80).
///
/// # Examples
///
/// ```
/// use android_support::authority_header_value;
///
/// // Default ports are omitted.
/// assert_eq!(authority_header_value("example.com", 443, true),  "example.com");
/// assert_eq!(authority_header_value("example.com", 80,  false), "example.com");
///
/// // Non-default ports are always included.
/// assert_eq!(authority_header_value("example.com", 8443, true),  "example.com:8443");
/// assert_eq!(authority_header_value("example.com", 8080, false), "example.com:8080");
///
/// // Port 80 on HTTPS and port 443 on HTTP are NOT defaults → include them.
/// assert_eq!(authority_header_value("example.com", 80,  true),  "example.com:80");
/// assert_eq!(authority_header_value("example.com", 443, false), "example.com:443");
/// ```
pub fn authority_header_value(host: &str, port: u16, https: bool) -> String {
    let default_port = if https { 443 } else { 80 };
    if port == default_port { host.to_string() } else { format!("{host}:{port}") }
}

#[cfg(test)]
mod tests {
    use super::authority_header_value;

    #[test]
    fn http_default_port_80_omitted() {
        assert_eq!(authority_header_value("example.com", 80, false), "example.com");
    }

    #[test]
    fn https_default_port_443_omitted() {
        assert_eq!(authority_header_value("example.com", 443, true), "example.com");
    }

    #[test]
    fn https_non_default_port_8443_included() {
        assert_eq!(authority_header_value("example.com", 8443, true), "example.com:8443");
    }

    #[test]
    fn https_port_80_not_default_included() {
        // Port 80 is NOT the default for HTTPS — must appear in the header.
        assert_eq!(authority_header_value("example.com", 80, true), "example.com:80");
    }

    #[test]
    fn http_port_443_not_default_included() {
        // Port 443 is NOT the default for HTTP — must appear in the header.
        assert_eq!(authority_header_value("example.com", 443, false), "example.com:443");
    }
}
