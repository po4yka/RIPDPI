use std::collections::HashMap;
use std::io;
use std::sync::Arc;

use ripdpi_collections::bounded_heap::BoundedHeap;
use smoltcp::iface::{Interface, SocketSet};
use tokio::sync::mpsc;
use tokio_util::sync::CancellationToken;
use tracing::info;

use ripdpi_tunnel_config::Config;

use crate::dns_cache::DnsCache;
use crate::session::udp::UdpMemoryBudget;
use crate::split_dns::SplitDnsPolicy;
use crate::{ActiveSessions, Stats, TunDevice};

use super::dns_intercept::{parse_dns_cache, parse_mapdns_runtime};
use super::retransmit::RetransmitTracker;
use super::setup_dns::{build_dns_worker, configure_resolver_fallback};
use super::state::{LoopState, PendingUidPackets};
use super::tcp_accept::{make_auth, proxy_addr};
use super::udp_assoc::UDP_EVICTION_HEAP_CAPACITY;

mod runtime;

use runtime::build_loop_runtime;

pub(in crate::io_loop) fn setup_io_loop(
    mut device: TunDevice,
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
    let split_dns_policy = config
        .split_dns_policy
        .as_ref()
        .map(SplitDnsPolicy::compile)
        .transpose()
        .map_err(|error| io::Error::other(format!("compile split DNS policy: {error}")))?;

    let max_sessions = config.misc.max_session_count as usize;
    let runtime = build_loop_runtime(&config, proxy_sockaddr, auth, mapdns_runtime, split_dns_policy);

    let (udp_tx, udp_rx) = mpsc::channel(256);
    let (dns_req_tx, dns_resp_rx) = build_dns_worker(&config, &cancel)?;
    let mtu = config.tunnel.mtu as usize;
    device.set_tun_queue_drop_stats(Arc::clone(&stats));

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
        tcp_admission_cursor: 0,
        loop_iteration: 0,
        udp_tx,
        udp_rx,
        udp_associations: HashMap::new(),
        udp_eviction_heap: BoundedHeap::new(UDP_EVICTION_HEAP_CAPACITY),
        udp_memory_budget: UdpMemoryBudget::for_tunnel_mtu(mtu),
        next_udp_association_id: 1,
        pending_uid_packets: PendingUidPackets::new(mtu + 64),
        dns_req_tx,
        dns_resp_rx,
        active_direct_dns_generation: None,
        tun_read_buf: vec![0u8; mtu + 64],
        retransmit_tracker: RetransmitTracker::new(),
        last_loss_emit_iteration: 0,
    })
}
