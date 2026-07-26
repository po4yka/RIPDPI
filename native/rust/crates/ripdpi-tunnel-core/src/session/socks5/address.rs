use std::net::SocketAddr;

/// Destination address for a SOCKS5 CONNECT or UDP ASSOCIATE request.
#[derive(Debug, Clone, PartialEq, Eq, Hash)]
pub enum TargetAddr {
    /// IPv4 or IPv6 socket address.
    Ip(SocketAddr),
    /// Fully-qualified domain name and port.
    Domain(String, u16),
    /// A MapDNS hostname paired with its already-authoritative target.
    ResolvedDomain(String, SocketAddr),
}
