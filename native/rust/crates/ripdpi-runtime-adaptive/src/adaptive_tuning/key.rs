use std::net::{IpAddr, SocketAddr};
use std::time::{SystemTime, UNIX_EPOCH};

use ripdpi_packets::is_quic_initial;

use ripdpi_runtime_policy::runtime_policy::is_tls_client_hello_payload;

use super::types::{AdaptiveFlowKind, AdaptivePlannerKey, AdaptivePlannerTarget};

const DEFAULT_NETWORK_SCOPE_KEY: &str = "default";
const FNV_OFFSET: u64 = 0xcbf29ce484222325;
const FNV_PRIME: u64 = 0x100000001b3;

pub(super) fn adaptive_key(
    network_scope_key: Option<&str>,
    group_index: usize,
    flow_kind: AdaptiveFlowKind,
    dest: SocketAddr,
    host: Option<&str>,
) -> AdaptivePlannerKey {
    AdaptivePlannerKey {
        network_scope_key: normalize_scope_key(network_scope_key).to_string(),
        group_index,
        flow_kind,
        target: normalized_host(host).map_or(AdaptivePlannerTarget::Address(dest), AdaptivePlannerTarget::Host),
    }
}

pub(super) fn tcp_flow_kind(payload: &[u8]) -> AdaptiveFlowKind {
    if is_tls_client_hello_payload(payload) { AdaptiveFlowKind::TcpTls } else { AdaptiveFlowKind::TcpOther }
}

pub(super) fn udp_flow_kind(payload: &[u8]) -> AdaptiveFlowKind {
    if is_quic_initial(payload) { AdaptiveFlowKind::UdpQuic } else { AdaptiveFlowKind::UdpOther }
}

fn normalized_host(host: Option<&str>) -> Option<String> {
    let trimmed = host?.trim().trim_end_matches('.');
    if trimmed.is_empty() {
        return None;
    }
    let normalized = trimmed.to_ascii_lowercase();
    if normalized.parse::<IpAddr>().is_ok() {
        return None;
    }
    Some(normalized)
}
pub(super) fn adaptive_seed(key: &AdaptivePlannerKey) -> u64 {
    let mut hash = FNV_OFFSET;
    stable_hash_update(&mut hash, key.network_scope_key.as_bytes());
    stable_hash_update(&mut hash, b"|");
    stable_hash_update(&mut hash, key.group_index.to_string().as_bytes());
    stable_hash_update(&mut hash, b"|");
    stable_hash_update(&mut hash, format!("{:?}", key.flow_kind).as_bytes());
    stable_hash_update(&mut hash, b"|");
    stable_hash_update(&mut hash, format!("{:?}", key.target).as_bytes());
    hash
}

pub(super) fn shuffled_dimensions(seed: u64) -> Vec<usize> {
    let mut dimensions = vec![0usize, 1, 2, 3, 4];
    dimensions.sort_by_key(|dimension| stable_hash(seed, *dimension as u64));
    dimensions
}

fn stable_hash(seed: u64, value: u64) -> u64 {
    let mut hash = FNV_OFFSET;
    stable_hash_update(&mut hash, seed.to_string().as_bytes());
    stable_hash_update(&mut hash, b"|");
    stable_hash_update(&mut hash, value.to_string().as_bytes());
    hash
}

fn stable_hash_update(hash: &mut u64, bytes: &[u8]) {
    for byte in bytes {
        *hash ^= u64::from(*byte);
        *hash = hash.wrapping_mul(FNV_PRIME);
    }
}

pub(super) fn now_millis() -> u64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .ok()
        .map_or(0, |value| value.as_millis().min(u128::from(u64::MAX)) as u64)
}

pub(super) fn normalize_scope_key(network_scope_key: Option<&str>) -> &str {
    network_scope_key.map(str::trim).filter(|value| !value.is_empty()).unwrap_or(DEFAULT_NETWORK_SCOPE_KEY)
}
