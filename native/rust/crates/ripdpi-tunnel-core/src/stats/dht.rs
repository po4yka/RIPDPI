use ipnet::Ipv4Net;
use std::net::{IpAddr, Ipv4Addr, SocketAddr};
use std::sync::atomic::Ordering;

use super::Stats;
use super::time;

const DHT_TRIGGER_NETWORKS: [Ipv4Net; 3] = [
    Ipv4Net::new_assert(Ipv4Addr::new(62, 210, 0, 0), 17),
    Ipv4Net::new_assert(Ipv4Addr::new(51, 159, 0, 0), 16),
    Ipv4Net::new_assert(Ipv4Addr::new(134, 195, 196, 0), 22),
];

pub(crate) fn record_trigger_destination(stats: &Stats, endpoint: SocketAddr) {
    if !is_trigger_destination(endpoint.ip()) {
        return;
    }

    stats.dht_trigger_observations.fetch_add(1, Ordering::Relaxed);
    if let Ok(mut guard) = stats.last_dht_trigger_endpoint.lock() {
        *guard = Some(endpoint.to_string());
    }
    stats.last_dht_trigger_at_ms.store(time::now_ms(), Ordering::Relaxed);
}

fn is_trigger_destination(ip: IpAddr) -> bool {
    let IpAddr::V4(ipv4) = ip else {
        return false;
    };

    DHT_TRIGGER_NETWORKS.iter().any(|network| network.contains(&ipv4))
}
