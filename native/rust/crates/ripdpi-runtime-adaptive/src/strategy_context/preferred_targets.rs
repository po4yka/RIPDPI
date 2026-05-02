use std::net::{IpAddr, SocketAddr};

use ripdpi_proxy_config::ProxyRuntimeContext;
use ripdpi_runtime_policy::runtime_policy::TransportProtocol;

use super::direct_path_capability::{
    capability_blocks_transport, capability_preserves_udp_transport, direct_path_capability_for_targets,
};

const MAX_PREFERRED_EDGE_TARGETS: usize = 2;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PreferredTargetsDecision {
    pub targets: Vec<SocketAddr>,
    pub suppressed_targets: Vec<SocketAddr>,
    pub suppressed_udp: bool,
}

pub fn preferred_targets_for_transport(
    runtime_context: Option<&ProxyRuntimeContext>,
    original_target: SocketAddr,
    host: Option<&str>,
    transport: TransportProtocol,
    now_millis: i64,
) -> PreferredTargetsDecision {
    let mut targets = preferred_edge_targets(runtime_context, original_target, host, transport);
    if !targets.contains(&original_target) {
        targets.push(original_target);
    }

    let Some(capability) = direct_path_capability_for_targets(runtime_context, host, &targets) else {
        return PreferredTargetsDecision { targets, suppressed_targets: Vec::new(), suppressed_udp: false };
    };
    if !capability_blocks_transport(capability, transport, now_millis) {
        return PreferredTargetsDecision { targets, suppressed_targets: Vec::new(), suppressed_udp: false };
    }

    let suppressed_udp = transport == TransportProtocol::Udp && !capability_preserves_udp_transport(capability);
    PreferredTargetsDecision { targets: Vec::new(), suppressed_targets: targets, suppressed_udp }
}

fn preferred_edge_targets(
    runtime_context: Option<&ProxyRuntimeContext>,
    original_target: SocketAddr,
    host: Option<&str>,
    transport: TransportProtocol,
) -> Vec<SocketAddr> {
    let Some(host) = host.map(str::trim).filter(|host| !host.is_empty()) else {
        return Vec::new();
    };
    let Some(runtime_context) = runtime_context else {
        return Vec::new();
    };

    let normalized = host.to_ascii_lowercase();
    let Some(edges) =
        runtime_context.preferred_edges.get(host).or_else(|| runtime_context.preferred_edges.get(&normalized))
    else {
        return Vec::new();
    };

    let mut targets = Vec::new();
    for edge in edges.iter().filter(|edge| preferred_edge_matches_transport(edge.transport_kind.trim(), transport)) {
        let Ok(ip) = edge.ip.trim().parse::<IpAddr>() else {
            continue;
        };
        let candidate = SocketAddr::new(ip, original_target.port());
        if !targets.contains(&candidate) {
            targets.push(candidate);
        }
        if targets.len() >= MAX_PREFERRED_EDGE_TARGETS {
            break;
        }
    }
    targets
}

fn preferred_edge_matches_transport(transport_kind: &str, transport: TransportProtocol) -> bool {
    match transport {
        TransportProtocol::Tcp => {
            transport_kind.eq_ignore_ascii_case("tcp") || transport_kind.eq_ignore_ascii_case("throughput")
        }
        TransportProtocol::Udp => transport_kind.eq_ignore_ascii_case("quic"),
    }
}
