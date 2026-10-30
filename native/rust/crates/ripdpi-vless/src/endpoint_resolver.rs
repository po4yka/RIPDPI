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

/// # Cancel safety
///
/// cancel-safe: see [`resolve_server_addrs`].
pub async fn resolve_server_addr(
    server: &str,
    port: u16,
    bind_ip: Option<IpAddr>,
    protect: Option<Box<SocketProtectFn>>,
) -> io::Result<SocketAddr> {
    resolve_server_addrs(server, port, bind_ip, protect)
        .await?
        .into_iter()
        .next()
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "relay server resolved to no addresses"))
}

/// Every usable relay server address, in resolver order and never empty. With a
/// `protect` callback the hostname goes through protected DoH, never system DNS.
///
/// # Cancel safety
///
/// cancel-safe: no caller-visible state spans an `.await`; a cancelled lookup
/// drops its resolver, and a blocking task already started finishes on its own.
pub async fn resolve_server_addrs(
    server: &str,
    port: u16,
    bind_ip: Option<IpAddr>,
    protect: Option<Box<SocketProtectFn>>,
) -> io::Result<Vec<SocketAddr>> {
    if let Ok(ip) = server.parse::<IpAddr>() {
        return Ok(vec![select_address_family(SocketAddr::new(ip, port), bind_ip, "relay server")?]);
    }

    match protect {
        Some(protect) => {
            resolve_hostname_via_doh(server, port, bind_ip, Some(protect), &PROTECTED_RELAY_DNS_RESOLVER_IDS).await
        }
        None => resolve_hostname_via_system_dns(server, port, bind_ip).await,
    }
}

/// Like [`resolve_server_addrs`], but a hostname never reaches the system
/// resolver: it goes through the same DoH pipeline and resolver ids, with
/// protected sockets when `protect` is `Some` and plain direct sockets when it
/// is `None`.
///
/// # Cancel safety
///
/// cancel-safe: see [`resolve_server_addrs`]; the lookup holds no state outside its future.
pub async fn resolve_server_addrs_encrypted(
    server: &str,
    port: u16,
    bind_ip: Option<IpAddr>,
    protect: Option<Box<SocketProtectFn>>,
) -> io::Result<Vec<SocketAddr>> {
    if let Ok(ip) = server.parse::<IpAddr>() {
        return Ok(vec![select_address_family(SocketAddr::new(ip, port), bind_ip, "relay server")?]);
    }
    resolve_hostname_via_doh(server, port, bind_ip, protect, encrypted_resolver_ids()).await
}

#[cfg(test)]
thread_local! {
    static TEST_RESOLVER_IDS: std::cell::Cell<Option<&'static [&'static str]>> = const { std::cell::Cell::new(None) };
}

/// The resolver ids of the encrypted lookup. Tests can override them to prove
/// that the public path fails inside the DoH builder and never reaches the system resolver.
fn encrypted_resolver_ids() -> &'static [&'static str] {
    #[cfg(test)]
    if let Some(ids) = TEST_RESOLVER_IDS.with(std::cell::Cell::get) {
        return ids;
    }
    &PROTECTED_RELAY_DNS_RESOLVER_IDS
}

/// One TCP connect to `target`, bounded by `timeout`, and the socket is closed
/// on return. With `protect` the socket is protected before connecting;
/// loopback targets never need protection and skip it.
///
/// # Cancel safety
///
/// cancel-safe: dropping it drops the connect. In protected mode, aborting
/// detaches the blocking connect, which ends within `timeout` after protect
/// returns and closes its socket. A short-lived caller such as resolve mode
/// exits the process explicitly, so the detached task cannot keep it alive.
pub async fn probe_tcp_reachable(
    target: SocketAddr,
    timeout: Duration,
    protect: Option<std::sync::Arc<Box<SocketProtectFn>>>,
) -> io::Result<()> {
    match protect {
        Some(protect) if !target.ip().is_loopback() => {
            connect_protected_bootstrap(target, None, timeout, protect).await.map(drop)
        }
        _ => tokio::time::timeout(timeout, tokio::net::TcpStream::connect(target))
            .await
            .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, "tcp probe timed out"))?
            .map(drop),
    }
}

fn matching_family(
    candidates: impl Iterator<Item = SocketAddr>,
    bind_ip: Option<IpAddr>,
) -> io::Result<Vec<SocketAddr>> {
    let addresses: Vec<SocketAddr> =
        candidates.filter(|address| bind_ip.is_none_or(|bind_ip| address.is_ipv4() == bind_ip.is_ipv4())).collect();
    if !addresses.is_empty() {
        return Ok(addresses);
    }
    let message = if bind_ip.is_some() {
        "relay server has no address matching outbound bind IP family"
    } else {
        "relay server resolved to no addresses"
    };
    Err(io::Error::new(io::ErrorKind::InvalidInput, message))
}

/// # Cancel safety
///
/// cancel-safe: dropping it detaches the blocking lookup, which owns its inputs.
async fn resolve_hostname_via_system_dns(
    server: &str,
    port: u16,
    bind_ip: Option<IpAddr>,
) -> io::Result<Vec<SocketAddr>> {
    let server = server.to_owned();
    tokio::task::spawn_blocking(move || {
        let candidates = (server.as_str(), port)
            .to_socket_addrs()
            .map_err(|error| io::Error::new(error.kind(), format!("resolve relay hostname: {error}")))?;
        matching_family(candidates, bind_ip)
    })
    .await
    .map_err(|error| io::Error::other(format!("system relay DNS task failed: {error}")))?
}

/// Without `protect` the pipeline keeps its default direct sockets.
///
/// # Cancel safety
///
/// cancel-safe: dropping it drops the resolver pipeline and its pending requests.
async fn resolve_hostname_via_doh(
    server: &str,
    port: u16,
    bind_ip: Option<IpAddr>,
    protect: Option<Box<SocketProtectFn>>,
    resolver_ids: &[&str],
) -> io::Result<Vec<SocketAddr>> {
    let hooks = match protect {
        Some(protect) => {
            let protect = std::sync::Arc::new(protect);
            EncryptedDnsConnectHooks::new().require_direct_tcp_connector().with_direct_tcp_connector(
                move |target, timeout| connect_protected_bootstrap(target, bind_ip, timeout, protect.clone()),
            )
        }
        None => EncryptedDnsConnectHooks::new(),
    };
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
    let candidates = doh_ip_answer_candidates(&lookup)
        .into_iter()
        .filter_map(|candidate| candidate.ip.parse::<IpAddr>().ok())
        .map(|ip| SocketAddr::new(ip, port));
    matching_family(candidates, bind_ip).map_err(|error| {
        io::Error::new(error.kind(), format!("protected relay DNS returned no usable address: {error}"))
    })
}

/// Runs the protect callback (a binder call on Android) and the blocking
/// connect on the blocking pool, so a blackholed bootstrap IP cannot park a
/// Tokio worker and the resolver's request timeout can preempt the await.
///
/// # Cancel safety
///
/// Cancel-safe: dropping the future detaches the blocking connect, which
/// ends within `timeout` after protect returns and closes its socket.
async fn connect_protected_bootstrap(
    target: SocketAddr,
    bind_ip: Option<IpAddr>,
    timeout: Duration,
    protect: std::sync::Arc<Box<SocketProtectFn>>,
) -> io::Result<std::net::TcpStream> {
    tokio::task::spawn_blocking(move || connect_protected_tcp_socket(target, bind_ip, timeout, protect.as_ref()))
        .await
        .map_err(|error| io::Error::other(format!("protected DNS bootstrap connect task failed: {error}")))?
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

    #[tokio::test(flavor = "current_thread")]
    async fn protected_bootstrap_connect_does_not_block_the_runtime() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind bootstrap fixture");
        let address = listener.local_addr().expect("bootstrap fixture address");
        // VpnService.protect is a binder call and connect_timeout can block for
        // seconds on a blackholed bootstrap IP; neither may run on a worker.
        let protect: Box<SocketProtectFn> = Box::new(|_| {
            std::thread::sleep(Duration::from_millis(1500));
            Ok(())
        });
        let protect = std::sync::Arc::new(protect);
        let started = std::time::Instant::now();

        let result = tokio::time::timeout(
            Duration::from_millis(100),
            connect_protected_bootstrap(address, None, PROTECTED_DNS_TIMEOUT, protect),
        )
        .await;

        assert!(result.is_err(), "the bootstrap connect must still be pending at the deadline");
        assert!(
            started.elapsed() < Duration::from_secs(1),
            "the connect blocked the Tokio worker for {:?}",
            started.elapsed()
        );
    }

    #[tokio::test]
    async fn protected_doh_requires_a_primary_resolver_id() {
        let protect: Box<SocketProtectFn> = Box::new(|_| Ok(()));
        let err = resolve_hostname_via_doh("relay.invalid", 443, None, Some(protect), &[])
            .await
            .expect_err("empty resolver id list must fail before any network I/O");

        assert!(err.to_string().contains("primary resolver id"), "unexpected error: {err}");
    }

    #[tokio::test]
    async fn unprotected_encrypted_lookup_uses_doh_not_system_dns() {
        // An empty id list fails inside the DoH builder; the system resolver has no such error.
        let err = resolve_hostname_via_doh("relay.invalid", 443, None, None, &[])
            .await
            .expect_err("empty resolver id list must fail before any network I/O");

        assert!(err.to_string().contains("primary resolver id"), "unexpected error: {err}");
    }

    #[tokio::test]
    async fn public_encrypted_lookup_never_uses_the_system_resolver() {
        // `localhost` resolves through getaddrinfo; with no resolver ids only the DoH builder can answer.
        TEST_RESOLVER_IDS.with(|ids| ids.set(Some(&[])));
        let result = resolve_server_addrs_encrypted("localhost", 443, None, None).await;
        TEST_RESOLVER_IDS.with(|ids| ids.set(None));

        let err = result.expect_err("an empty resolver id list must not fall back to system DNS");
        assert!(err.to_string().contains("primary resolver id"), "unexpected error: {err}");
    }

    #[tokio::test]
    async fn encrypted_lookup_echoes_ip_literals() {
        let addrs = resolve_server_addrs_encrypted("192.0.2.9", 443, None, None).await.expect("literal");

        assert_eq!(addrs, vec![SocketAddr::new(IpAddr::from([192, 0, 2, 9]), 443)]);
    }

    #[tokio::test]
    async fn probe_accepts_an_open_port_and_rejects_a_closed_one() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").expect("bind");
        let open = listener.local_addr().expect("addr");
        let closed = {
            let l = std::net::TcpListener::bind("127.0.0.1:0").expect("bind");
            l.local_addr().expect("addr")
        };

        assert!(probe_tcp_reachable(open, Duration::from_secs(1), None).await.is_ok());
        assert!(probe_tcp_reachable(closed, Duration::from_secs(1), None).await.is_err());
    }
}
