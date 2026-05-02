use std::sync::atomic::Ordering;

use super::Stats;

pub(crate) fn snapshot(stats: &Stats) -> (u64, u64, u64, u64) {
    (
        stats.tx_packets.load(Ordering::Relaxed),
        stats.tx_bytes.load(Ordering::Relaxed),
        stats.rx_packets.load(Ordering::Relaxed),
        stats.rx_bytes.load(Ordering::Relaxed),
    )
}
