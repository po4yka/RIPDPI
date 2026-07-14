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
use ripdpi_tunnel_intercept::egress::{RawTunPacketInjector, TunEgressInterceptor};
use ripdpi_tunnel_intercept::ingress::{RawSynAckPacketInjector, SynAckStrategy, TunIngressInterceptor};

use crate::dns_cache::DnsCache;
use crate::session::udp::UdpMemoryBudget;
use crate::uid_policy::UidFlowPolicy;
use crate::{ActiveSessions, Stats, TunDevice};

use super::dns_intercept::{parse_dns_cache, parse_mapdns_runtime};
use super::retransmit::RetransmitTracker;
use super::setup_dns::{build_dns_worker, configure_resolver_fallback};
use super::state::{LoopRuntime, LoopState};
use super::tcp_accept::{make_auth, proxy_addr};
use super::udp_assoc::UDP_EVICTION_HEAP_CAPACITY;

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
    let policy_uids = config.misc.uid_policy_uids.iter().copied().collect();
    let uid_policy = match config.misc.uid_policy_mode.as_str() {
        "allowlist" => UidFlowPolicy::enforcing(policy_uids),
        "denylist" => UidFlowPolicy::denying(policy_uids),
        _ => UidFlowPolicy::disarmed(),
    };
    let runtime = LoopRuntime {
        proxy_sockaddr,
        auth,
        mapdns_runtime,
        mapdns_classify,
        filter_injected_resets: config.misc.filter_injected_resets,
        uid_policy,
        tun_ingress_interceptor: TunIngressInterceptor::new(
            SynAckStrategy::from_yaml(config.misc.strategy_chain_yaml.as_deref()),
            RawSynAckPacketInjector::new(config.misc.protect_path.clone()),
        ),
        // Jail the egress `lua`-step `script_paths` to the app's absolute
        // `<filesDir>/lua` dir when supplied, not `"."` (ill-defined Android CWD).
        tun_egress_interceptor: Box::new(TunEgressInterceptor::new_with_base_dir(
            config.misc.strategy_chain_yaml.as_deref(),
            config.misc.lua_script_base_dir.as_deref().map_or(std::path::Path::new("."), std::path::Path::new),
            RawTunPacketInjector::new(config.misc.protect_path.clone()),
        )),
        udp_idle_timeout: Duration::from_millis(u64::from(config.misc.udp_read_write_timeout)),
        tcp_connect_timeout: Duration::from_millis(u64::from(config.misc.connect_timeout)),
        tcp_read_write_timeout: Duration::from_millis(u64::from(config.misc.tcp_read_write_timeout)),
        protect_path: config.misc.protect_path.clone(),
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
        tcp_admission_cursor: 0,
        loop_iteration: 0,
        udp_tx,
        udp_rx,
        udp_associations: HashMap::new(),
        udp_eviction_heap: BoundedHeap::new(UDP_EVICTION_HEAP_CAPACITY),
        udp_memory_budget: UdpMemoryBudget::for_tunnel_mtu(mtu),
        next_udp_association_id: 1,
        dns_req_tx,
        dns_resp_rx,
        tun_read_buf: vec![0u8; mtu + 64],
        retransmit_tracker: RetransmitTracker::new(),
        last_loss_emit_iteration: 0,
    })
}
