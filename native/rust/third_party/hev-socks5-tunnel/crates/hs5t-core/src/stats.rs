use std::sync::atomic::{AtomicU64, Ordering};

/// Per-tunnel traffic counters.
///
/// All fields are `AtomicU64` with `Relaxed` ordering (no inter-thread synchronisation
/// is required beyond visibility — the values are read only for reporting).
pub struct Stats {
    /// Count of IP packets read from the TUN fd (inbound from OS).
    pub tx_packets: AtomicU64,
    /// Total bytes of packets read from TUN fd.
    pub tx_bytes: AtomicU64,
    /// Count of IP packets written to the TUN fd (outbound to OS).
    pub rx_packets: AtomicU64,
    /// Total bytes of packets written to TUN fd.
    pub rx_bytes: AtomicU64,
}

impl Default for Stats {
    fn default() -> Self {
        Self::new()
    }
}

impl Stats {
    pub fn new() -> Self {
        Self {
            tx_packets: AtomicU64::new(0),
            tx_bytes: AtomicU64::new(0),
            rx_packets: AtomicU64::new(0),
            rx_bytes: AtomicU64::new(0),
        }
    }

    /// Returns a point-in-time snapshot: `(tx_packets, tx_bytes, rx_packets, rx_bytes)`.
    pub fn snapshot(&self) -> (u64, u64, u64, u64) {
        (
            self.tx_packets.load(Ordering::Relaxed),
            self.tx_bytes.load(Ordering::Relaxed),
            self.rx_packets.load(Ordering::Relaxed),
            self.rx_bytes.load(Ordering::Relaxed),
        )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// U-08: Statistics counters increment correctly on rx/tx.
    #[test]
    fn u08_stats_counters_increment() {
        let stats = Stats::new();

        // Simulate reading 2 packets from TUN (tx direction).
        stats.tx_packets.fetch_add(1, Ordering::Relaxed);
        stats.tx_bytes.fetch_add(100, Ordering::Relaxed);
        stats.tx_packets.fetch_add(1, Ordering::Relaxed);
        stats.tx_bytes.fetch_add(200, Ordering::Relaxed);

        // Simulate writing 1 packet to TUN (rx direction).
        stats.rx_packets.fetch_add(1, Ordering::Relaxed);
        stats.rx_bytes.fetch_add(150, Ordering::Relaxed);

        let (tx_pkts, tx_bytes, rx_pkts, rx_bytes) = stats.snapshot();
        assert_eq!(tx_pkts, 2, "tx_packets must be 2");
        assert_eq!(tx_bytes, 300, "tx_bytes must be 300");
        assert_eq!(rx_pkts, 1, "rx_packets must be 1");
        assert_eq!(rx_bytes, 150, "rx_bytes must be 150");
    }

    /// Stats start at zero.
    #[test]
    fn stats_start_at_zero() {
        let s = Stats::new();
        assert_eq!(s.snapshot(), (0, 0, 0, 0));
    }
}
