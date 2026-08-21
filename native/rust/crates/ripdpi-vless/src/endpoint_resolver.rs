use std::io;
use std::net::{IpAddr, SocketAddr, ToSocketAddrs};
use std::os::fd::{AsRawFd, RawFd};
use std::time::Duration;

use ripdpi_dns_resolver::{
    DohResolverPipeline, EncryptedDnsConnectHooks, EncryptedDnsTransport, doh_ip_answer_candidates,
};
use socket2::{Domain, Protocol, SockAddr, Socket, Type};

const PROTECTED_DNS_TIMEOUT: Duration = Duration::from_secs(5);

/// Resolver IDs used to bootstrap relay-hostname resolution when socket
/// protection is active. Both entries come from the shared encrypted-DNS
/// catalog; changing this set changes which third-party resolvers see
/// relay-hostname lookups, so it stays one named decision here instead of
/// inline string literals.
const PROTECTED_RELAY_DNS_RESOLVER_IDS: [&str; 2] = ["adguard", "dnssb"];

pub type SocketProtectFn = dyn Fn(RawFd) -> io::Result<()> + Send + Sync + 'static;

pub async fn resolve_server_addr(
    server: &str,
    port: u16,
    bind_ip: Option<IpAddr>,
    protect: Option<Box<SocketProtectFn>>,
) -> io::Result<SocketAddr> {
    if let Ok(ip) = server.parse::<IpAddr>() {
        return select_address_family(SocketAddr::new(ip, port), bind_ip, "relay server");
    }

    match protect {
        Some(protect) => {
            resolve_hostname_via_protected_doh(server, port, bind_ip, protect, &PROTECTED_RELAY_DNS_RESOLVER_IDS).await
        }
        None => resolve_hostname_via_system_dns(server, port, bind_ip).await,
    }
}

async fn resolve_hostname_via_system_dns(server: &str, port: u16, bind_ip: Option<IpAddr>) -> io::Result<SocketAddr> {
    let server = server.to_owned();
    tokio::task::spawn_blocking(move || {
        let mut candidates = (server.as_str(), port)
            .to_socket_addrs()
            .map_err(|error| io::Error::new(error.kind(), format!("resolve relay hostname: {error}")))?;
        match bind_ip {
            Some(bind_ip) => candidates.find(|address| address.is_ipv4() == bind_ip.is_ipv4()).ok_or_else(|| {
                io::Error::new(
                    io::ErrorKind::InvalidInput,
                    "relay server has no address matching outbound bind IP family",
                )
            }),
            None => candidates
                .next()
                .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "relay server resolved to no addresses")),
        }
    })
    .await
    .map_err(|error| io::Error::other(format!("system relay DNS task failed: {error}")))?
}

async fn resolve_hostname_via_protected_doh(
    server: &str,
    port: u16,
    bind_ip: Option<IpAddr>,
    protect: Box<SocketProtectFn>,
    resolver_ids: &[&str],
) -> io::Result<SocketAddr> {
    let protect = std::sync::Arc::new(protect);
    let hooks = EncryptedDnsConnectHooks::new().require_direct_tcp_connector().with_direct_tcp_connector(
        move |target, timeout| {
            let protect = protect.clone();
            async move { connect_protected_tcp_socket(target, bind_ip, timeout, protect.as_ref()) }
        },
    );
    let primary_id = resolver_ids.first().ok_or_else(|| {
        io::Error::new(io::ErrorKind::InvalidInput, "protected relay DNS needs a primary resolver id")
    })?;
    let secondary = resolver_ids.get(1).map(|id| ripdpi_ech_dns::encrypted_dns_endpoint_for_resolver_id(id));
    let mut builder = DohResolverPipeline::builder()
        .primary_endpoint(ripdpi_ech_dns::encrypted_dns_endpoint_for_resolver_id(primary_id));
    if let Some(secondary) = secondary {
        builder = builder.secondary_endpoint(secondary);
    }
    let pipeline = builder
        .transport(EncryptedDnsTransport::Direct)
        .timeout(PROTECTED_DNS_TIMEOUT)
        .connect_hooks(hooks)
        .build()
        .map_err(|error| io::Error::other(format!("build protected relay DNS resolver: {error}")))?;
    let lookup = pipeline
        .resolve(server)
        .await
        .map_err(|error| io::Error::other(format!("resolve relay hostname through protected DNS: {error}")))?;
    doh_ip_answer_candidates(&lookup)
        .into_iter()
        .filter_map(|candidate| candidate.ip.parse::<IpAddr>().ok())
        .find(|ip| bind_ip.is_none_or(|bind_ip| ip.is_ipv4() == bind_ip.is_ipv4()))
        .map(|ip| SocketAddr::new(ip, port))
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "protected relay DNS returned no usable address"))
}

fn connect_protected_tcp_socket(
    target: SocketAddr,
    bind_ip: Option<IpAddr>,
    timeout: Duration,
    protect: &SocketProtectFn,
) -> io::Result<std::net::TcpStream> {
    let socket = Socket::new(domain_for(target), Type::STREAM, Some(Protocol::TCP))?;
    protect(socket.as_raw_fd())?;
    if let Some(bind_ip) = bind_ip {
        let bind_addr =
            select_address_family(SocketAddr::new(bind_ip, 0), Some(target.ip()), "protected DNS bootstrap")?;
        socket.bind(&SockAddr::from(bind_addr))?;
    }
    socket.connect_timeout(&SockAddr::from(target), timeout)?;
    let stream: std::net::TcpStream = socket.into();
    stream.set_nodelay(true)?;
    Ok(stream)
}

fn select_address_family(address: SocketAddr, bind_ip: Option<IpAddr>, label: &str) -> io::Result<SocketAddr> {
    if bind_ip.is_some_and(|bind_ip| address.is_ipv4() != bind_ip.is_ipv4()) {
        return Err(io::Error::new(
            io::ErrorKind::InvalidInput,
            format!("{label} address family does not match outbound bind IP family"),
        ));
    }
    Ok(address)
}

fn domain_for(address: SocketAddr) -> Domain {
    match address {
        SocketAddr::V4(_) => Domain::IPV4,
        SocketAddr::V6(_) => Domain::IPV6,
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn ip_literal_resolution_does_not_require_protector() {
        let resolved = resolve_server_addr("192.0.2.1", 443, None, None).await.expect("ip literal resolves");

        assert_eq!(resolved, SocketAddr::new(IpAddr::from([192, 0, 2, 1]), 443));
    }

    #[tokio::test]
    async fn hostname_with_required_protector_fails_before_system_dns() {
        let err = resolve_server_addr(
            "relay.invalid",
            443,
            None,
            Some(Box::new(|_| Err(io::Error::new(io::ErrorKind::PermissionDenied, "protected resolver sentinel")))),
        )
        .await
        .expect_err("protected hostname resolution must fail closed through the protector");

        assert!(err.to_string().contains("protected resolver sentinel"), "unexpected protected resolver error: {err}",);
    }

    #[tokio::test]
    async fn protected_doh_requires_a_primary_resolver_id() {
        let protect: Box<SocketProtectFn> = Box::new(|_| Ok(()));
        let err = resolve_hostname_via_protected_doh("relay.invalid", 443, None, protect, &[])
            .await
            .expect_err("empty resolver id list must fail before any network I/O");

        assert!(err.to_string().contains("primary resolver id"), "unexpected error: {err}");
    }
}
