use std::collections::HashMap;
use std::io;
use std::net::IpAddr;
use std::sync::Arc;
use std::time::Duration;

use ripdpi_collections::bounded_heap::BoundedHeap;
use smoltcp::iface::{Interface, SocketSet};
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;
use tracing::info;

use ripdpi_tunnel_config::Config;

use crate::dns_cache::DnsCache;
use crate::{ActiveSessions, Stats, TunDevice};

use super::dns_intercept::{build_encrypted_dns_resolver, parse_dns_cache, parse_mapdns_runtime, spawn_dns_worker};
use super::state::{LoopRuntime, LoopState};
use super::tcp_accept::{make_auth, proxy_addr};
use super::udp_assoc::DEFAULT_MAX_UDP_ASSOCIATIONS;

pub(in crate::io_loop) fn setup_io_loop(
    device: TunDevice,
    iface: Interface,
    socket_set: SocketSet<'static>,
    sessions: ActiveSessions,
    config: Arc<Config>,
    cancel: CancellationToken,
    stats: Arc<Stats>,
    mut dns_cache: Option<DnsCache>,
) -> io::Result<LoopState> {
    let proxy_sockaddr =
        proxy_addr(&config).map_err(|e| io::Error::other(format!("resolve SOCKS5 proxy address: {e}")))?;
    let auth = make_auth(&config);

    let mapdns_runtime =
        parse_mapdns_runtime(&config).map_err(|e| io::Error::other(format!("parse mapdns runtime config: {e}")))?;
    dns_cache =
        parse_dns_cache(&config, dns_cache).map_err(|e| io::Error::other(format!("initialize DNS cache: {e}")))?;
    configure_resolver_fallback(&config, &stats);

    let mapdns_classify = mapdns_runtime.map(|value| {
        (
            match value.intercept_addr.ip() {
                IpAddr::V4(v4) => u32::from(v4),
                IpAddr::V6(_) => unreachable!("mapdns runtime only supports IPv4"),
            },
            u32::MAX,
            value.intercept_port,
        )
    });
    let max_sessions = config.misc.max_session_count as usize;
    let runtime = LoopRuntime {
        proxy_sockaddr,
        auth,
        mapdns_runtime,
        mapdns_classify,
        filter_injected_resets: config.misc.filter_injected_resets,
        udp_idle_timeout: Duration::from_millis(u64::from(config.misc.udp_read_write_timeout)),
    };

    let (udp_tx, udp_rx) = mpsc::channel(256);
    let (dns_req_tx, dns_resp_rx) = build_dns_worker(&config, &cancel)?;
    let mtu = config.tunnel.mtu as usize;

    info!("io_loop started (proxy={}, max_sessions={})", proxy_sockaddr, max_sessions);

    Ok(LoopState {
        device,
        iface,
        socket_set,
        sessions,
        cancel,
        stats,
        dns_cache,
        runtime,
        pending_listens: HashMap::new(),
        loop_iteration: 0,
        udp_tx,
        udp_rx,
        udp_associations: HashMap::new(),
        udp_eviction_heap: BoundedHeap::new(DEFAULT_MAX_UDP_ASSOCIATIONS),
        next_udp_association_id: 1,
        dns_req_tx,
        dns_resp_rx,
        tun_read_buf: vec![0u8; mtu + 64],
    })
}

fn configure_resolver_fallback(config: &Config, stats: &Arc<Stats>) {
    if let Some(mapdns) = config.mapdns.as_ref() {
        stats.configure_resolver_fallback(mapdns.resolver_fallback_active, mapdns.resolver_fallback_reason.as_deref());
    }
}

fn build_dns_worker(
    config: &Config,
    cancel: &CancellationToken,
) -> io::Result<(
    Option<tokio::sync::mpsc::Sender<super::dns_intercept::DnsRequest>>,
    Option<tokio::sync::mpsc::Receiver<super::dns_intercept::DnsResponse>>,
)> {
    let Some(resolver) = build_encrypted_dns_resolver(config)
        .map_err(|e| io::Error::other(format!("build encrypted DNS resolver: {e}")))?
    else {
        return Ok((None, None));
    };

    let (tx, rx) = spawn_dns_worker(resolver, cancel.child_token());
    Ok((Some(tx), Some(rx)))
}
