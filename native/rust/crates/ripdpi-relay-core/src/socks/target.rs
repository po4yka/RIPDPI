use std::fmt;
use std::io;
use std::net::SocketAddr;

#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub(crate) enum RelayTargetAddr {
    Ip(SocketAddr),
    Domain(String, u16),
}

impl RelayTargetAddr {
    pub(crate) fn to_connect_target(&self) -> String {
        match self {
            Self::Ip(addr) => addr.to_string(),
            Self::Domain(host, port) => format!("{host}:{port}"),
        }
    }

    pub(crate) fn from_authority(authority: &str) -> io::Result<Self> {
        if let Ok(addr) = authority.parse::<SocketAddr>() {
            return Ok(Self::Ip(addr));
        }

        let (host, port) = authority.rsplit_once(':').ok_or_else(|| {
            io::Error::new(io::ErrorKind::InvalidInput, format!("invalid target authority: {authority}"))
        })?;
        let port = port.parse::<u16>().map_err(|_| {
            io::Error::new(io::ErrorKind::InvalidInput, format!("invalid target port in authority: {authority}"))
        })?;
        Ok(Self::Domain(host.to_string(), port))
    }
}

impl fmt::Display for RelayTargetAddr {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        match self {
            Self::Ip(addr) => addr.fmt(f),
            Self::Domain(host, port) => write!(f, "{host}:{port}"),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::RelayTargetAddr;

    #[test]
    fn relay_target_parses_ip_and_domain_authorities() {
        assert_eq!(
            RelayTargetAddr::from_authority("1.1.1.1:53").expect("ipv4"),
            RelayTargetAddr::Ip("1.1.1.1:53".parse().expect("socket addr"))
        );
        assert_eq!(
            RelayTargetAddr::from_authority("example.com:443").expect("domain"),
            RelayTargetAddr::Domain("example.com".to_string(), 443)
        );
    }
}
