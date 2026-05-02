use std::net::{IpAddr, SocketAddr};
use std::sync::atomic::Ordering;

use super::time;
use super::Stats;

const DHT_TRIGGER_SCALAWAY_62_NET: u32 = u32::from_be_bytes([62, 210, 0, 0]);
const DHT_TRIGGER_SCALAWAY_62_MASK: u32 = 0xFFFF_8000;
const DHT_TRIGGER_SCALAWAY_51_NET: u32 = u32::from_be_bytes([51, 159, 0, 0]);
const DHT_TRIGGER_SCALAWAY_51_MASK: u32 = 0xFFFF_0000;
const DHT_TRIGGER_GLOBALTELEHOST_NET: u32 = u32::from_be_bytes([134, 195, 196, 0]);
const DHT_TRIGGER_GLOBALTELEHOST_MASK: u32 = 0xFFFF_FC00;

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

    let value = u32::from(ipv4);
    cidr_contains(value, DHT_TRIGGER_SCALAWAY_62_NET, DHT_TRIGGER_SCALAWAY_62_MASK)
        || cidr_contains(value, DHT_TRIGGER_SCALAWAY_51_NET, DHT_TRIGGER_SCALAWAY_51_MASK)
        || cidr_contains(value, DHT_TRIGGER_GLOBALTELEHOST_NET, DHT_TRIGGER_GLOBALTELEHOST_MASK)
}

fn cidr_contains(value: u32, network: u32, mask: u32) -> bool {
    value & mask == network
}
