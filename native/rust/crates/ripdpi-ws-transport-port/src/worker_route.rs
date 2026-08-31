use std::io;

#[derive(Clone, PartialEq, Eq)]
pub struct WorkerBearer(String);

impl WorkerBearer {
    pub fn parse(value: impl Into<String>) -> io::Result<Self> {
        let value = value.into();
        if value.is_empty() || value.len() > 4096 || !is_rfc6750_bearer_token(value.as_bytes()) {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "Cloudflare Worker bearer must be a bounded RFC 6750 bearer token",
            ));
        }
        Ok(Self(value))
    }

    pub fn expose_secret(&self) -> &str {
        &self.0
    }
}

impl std::fmt::Debug for WorkerBearer {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("WorkerBearer(<redacted>)")
    }
}

fn is_rfc6750_bearer_token(bearer: &[u8]) -> bool {
    let padding_start = bearer.iter().position(|byte| *byte == b'=').unwrap_or(bearer.len());
    padding_start > 0
        && bearer[..padding_start].iter().all(|byte| byte.is_ascii_alphanumeric() || b"-._~+/".contains(byte))
        && bearer[padding_start..].iter().all(|byte| *byte == b'=')
}

/// Validated optional Cloudflare Worker route for the Telegram WS tunnel.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CloudflareWorkerRoute {
    host: String,
    request_authority: String,
    port: u16,
    request_path: String,
    bearer: WorkerBearer,
}

impl CloudflareWorkerRoute {
    pub fn parse(url: impl AsRef<str>, bearer: impl Into<String>) -> io::Result<Self> {
        Self::parse_str(url.as_ref(), bearer.into())
    }

    fn parse_str(url: &str, bearer: String) -> io::Result<Self> {
        validate_worker_url_characters(url)?;
        let without_scheme = if let Some(rest) = url.strip_prefix("https://") {
            rest
        } else if let Some(rest) = url.strip_prefix("wss://") {
            rest
        } else {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "Cloudflare Worker URL scheme must be https or wss",
            ));
        };
        if without_scheme.contains('#') {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "Cloudflare Worker URL must not contain a fragment",
            ));
        }
        let authority_end = without_scheme.find(['/', '?']).unwrap_or(without_scheme.len());
        let (authority, suffix) = without_scheme.split_at(authority_end);
        if authority.is_empty() {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "Cloudflare Worker URL must contain a hostname"));
        }
        if authority.contains('@') {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "Cloudflare Worker URL must not contain userinfo"));
        }
        let (host, port) = parse_worker_authority(authority)?;
        let request_path = if suffix.is_empty() {
            "/".to_string()
        } else if suffix.starts_with('?') {
            format!("/{suffix}")
        } else {
            suffix.to_string()
        };
        Ok(Self {
            host,
            request_authority: authority.to_string(),
            port,
            request_path,
            bearer: WorkerBearer::parse(bearer)?,
        })
    }

    pub fn host(&self) -> &str {
        &self.host
    }

    pub fn port(&self) -> u16 {
        self.port
    }

    pub fn request_authority(&self) -> &str {
        &self.request_authority
    }

    pub fn request_path(&self) -> &str {
        &self.request_path
    }

    pub fn bearer(&self) -> &WorkerBearer {
        &self.bearer
    }
}

fn parse_worker_authority(authority: &str) -> io::Result<(String, u16)> {
    if let Some(stripped) = authority.strip_prefix('[') {
        let (host, rest) = stripped.split_once(']').ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidInput, "Cloudflare Worker IPv6 host is missing closing bracket")
        })?;
        host.parse::<std::net::Ipv6Addr>()
            .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "invalid Cloudflare Worker IPv6 hostname"))?;
        let port = if rest.is_empty() {
            443
        } else {
            parse_worker_port(rest.strip_prefix(':').ok_or_else(|| {
                io::Error::new(io::ErrorKind::InvalidInput, "Cloudflare Worker IPv6 host has invalid port delimiter")
            })?)?
        };
        return Ok((host.to_string(), port));
    }
    if let Some((host, port)) = authority.rsplit_once(':') {
        if host.contains(':') {
            return Err(io::Error::new(io::ErrorKind::InvalidInput, "Cloudflare Worker IPv6 host must use brackets"));
        }
        validate_worker_hostname(host)?;
        return Ok((host.to_string(), parse_worker_port(port)?));
    }
    if authority.contains(':') {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "Cloudflare Worker IPv6 host must use brackets"));
    }
    validate_worker_hostname(authority)?;
    Ok((authority.to_string(), 443))
}

fn validate_worker_url_characters(url: &str) -> io::Result<()> {
    if !url.is_ascii() || url.bytes().any(|byte| byte <= b' ' || byte == 0x7f) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            "Cloudflare Worker URL must contain only visible ASCII characters",
        ));
    }
    let bytes = url.as_bytes();
    for (index, byte) in bytes.iter().copied().enumerate() {
        if !byte.is_ascii_alphanumeric() && !b"-._~:/?[]@!$&'()*+,;=%".contains(&byte) {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "Cloudflare Worker URL contains an invalid URI character",
            ));
        }
        if byte == b'%'
            && (index + 2 >= bytes.len()
                || !bytes[index + 1].is_ascii_hexdigit()
                || !bytes[index + 2].is_ascii_hexdigit())
        {
            return Err(io::Error::new(
                io::ErrorKind::InvalidInput,
                "Cloudflare Worker URL contains invalid percent encoding",
            ));
        }
    }
    Ok(())
}

fn validate_worker_hostname(host: &str) -> io::Result<()> {
    if host.is_empty()
        || host.split('.').any(|label| {
            label.is_empty()
                || label.starts_with('-')
                || label.ends_with('-')
                || !label.bytes().all(|byte| byte.is_ascii_alphanumeric() || byte == b'-')
        })
    {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "invalid Cloudflare Worker hostname"));
    }
    Ok(())
}

fn parse_worker_port(port: &str) -> io::Result<u16> {
    port.parse::<u16>()
        .ok()
        .filter(|port| *port != 0)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "invalid Cloudflare Worker port"))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn worker_route_parses_https_authority_path_and_bearer() {
        let route = CloudflareWorkerRoute::parse("https://edge.example.workers.dev:8443/relay?dc=2", "secret-token")
            .expect("valid worker route");

        assert_eq!(route.host(), "edge.example.workers.dev");
        assert_eq!(route.port(), 8443);
        assert_eq!(route.request_authority(), "edge.example.workers.dev:8443");
        assert_eq!(route.request_path(), "/relay?dc=2");
        assert_eq!(route.bearer().expose_secret(), "secret-token");
    }

    #[test]
    fn worker_bearer_debug_redacts_secret() {
        let bearer = WorkerBearer::parse("secret-token").expect("valid bearer");

        assert_eq!(format!("{bearer:?}"), "WorkerBearer(<redacted>)");
    }

    #[test]
    fn worker_route_rejects_userinfo_and_invalid_ports() {
        assert!(CloudflareWorkerRoute::parse("https://user@edge.example.workers.dev/relay", "secret-token").is_err());
        assert!(CloudflareWorkerRoute::parse("https://edge.example.workers.dev:0/relay", "secret-token").is_err());
    }
}
