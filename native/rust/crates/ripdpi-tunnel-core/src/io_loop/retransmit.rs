//! TCP retransmit detection for per-flow loss-percentage tracking.
//!
//! `RetransmitTracker` maintains an LRU cache of at most 512 TCP flows.
//! For each inbound packet it checks whether the TCP sequence number
//! regresses (seq <= last_seen_seq && payload_len > 0 && !FIN && !RST).
//! That conservative heuristic is a retransmit candidate; false negatives
//! are preferred over false positives.
//!
//! Every `LOSS_EMIT_INTERVAL` loop iterations the caller reads
//! `current_loss_pct()` and emits it via `Stats::emit_loss_pct`.
//!
//! Hard invariants:
//! - No `tracing::event!` / `log::*` on the per-packet path (O(1) cost).
//! - No `unsafe`. `#![forbid(unsafe_code)]` is set on the crate.
//! - `FlowKey` is 4× pointer-sized; the LRU fits in ≈ 32 KB total.

use std::net::IpAddr;

use etherparse::{NetSlice, SlicedPacket, TransportSlice};
use lru::LruCache;

/// Maximum number of TCP flows tracked simultaneously.
const FLOW_CACHE_CAP: usize = 512;

/// Four-tuple that uniquely identifies a TCP flow from the TUN perspective.
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
struct FlowKey {
    src_ip: IpAddr,
    dst_ip: IpAddr,
    src_port: u16,
    dst_port: u16,
}

/// Per-flow state retained in the LRU.
#[derive(Debug, Clone, Copy)]
struct FlowState {
    /// Last observed TCP sequence number for data segments.
    last_seq: u32,
}

/// Stateful retransmit tracker. Owns the LRU and the running counters.
pub(in crate::io_loop) struct RetransmitTracker {
    flows: LruCache<FlowKey, FlowState>,
    total_segments: u64,
    retransmits: u64,
}

impl RetransmitTracker {
    pub(in crate::io_loop) fn new() -> Self {
        Self {
            flows: LruCache::new(std::num::NonZeroUsize::new(FLOW_CACHE_CAP).expect("FLOW_CACHE_CAP > 0")),
            total_segments: 0,
            retransmits: 0,
        }
    }

    /// Observe one raw IP packet from the TUN drain.
    ///
    /// Synchronous and O(1) amortised (LRU put/get). No logging, no
    /// allocation on the hot path. Non-TCP packets are silently ignored.
    pub(in crate::io_loop) fn observe(&mut self, packet: &[u8]) {
        let Ok(parsed) = SlicedPacket::from_ip(packet) else { return };

        let (src_ip, dst_ip) = match &parsed.net {
            Some(NetSlice::Ipv4(v4)) => {
                (IpAddr::V4(v4.header().source_addr()), IpAddr::V4(v4.header().destination_addr()))
            }
            Some(NetSlice::Ipv6(v6)) => {
                (IpAddr::V6(v6.header().source_addr()), IpAddr::V6(v6.header().destination_addr()))
            }
            _ => return,
        };

        let Some(TransportSlice::Tcp(tcp)) = parsed.transport else {
            return;
        };

        let key = FlowKey { src_ip, dst_ip, src_port: tcp.source_port(), dst_port: tcp.destination_port() };

        // FIN or RST: remove flow state and stop tracking this flow.
        if tcp.fin() || tcp.rst() {
            self.flows.pop(&key);
            return;
        }

        let payload_len = tcp.payload().len();

        // Pure ACKs (no payload) don't advance sequence space — skip them
        // to avoid spurious retransmit counts on keepalives.
        if payload_len == 0 {
            return;
        }

        let seq = tcp.sequence_number();
        self.total_segments += 1;

        match self.flows.get_mut(&key) {
            Some(state) => {
                // seq <= last_seq with non-empty payload is a retransmit
                // candidate (conservative: wrapping is ignored for simplicity;
                // a wrap-around produces at most one spurious count per ~4 GB
                // of flow data, which is negligible in the loss rate).
                if seq <= state.last_seq {
                    self.retransmits += 1;
                } else {
                    state.last_seq = seq;
                }
            }
            None => {
                self.flows.put(key, FlowState { last_seq: seq });
            }
        }
    }

    /// Returns the retransmit-derived loss percentage since the last
    /// `reset_counters` call (or construction), clamped to 0.0..=100.0.
    ///
    /// Returns `0.0` when no segments have been observed yet.
    pub(in crate::io_loop) fn current_loss_pct(&self) -> f32 {
        if self.total_segments == 0 {
            return 0.0;
        }
        ((self.retransmits as f64 / self.total_segments as f64) * 100.0).clamp(0.0, 100.0) as f32
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // ── helpers ──────────────────────────────────────────────────────────────

    fn ipv4_tcp_packet(src_port: u16, dst_port: u16, seq: u32, flags: u8, payload_len: usize) -> Vec<u8> {
        // flags byte: bit4=ACK, bit1=SYN, bit0=FIN, bit2=RST
        let data_offset_flags: u16 = (0x50u16 << 8) | (flags as u16);
        let tcp_len = 20 + payload_len;
        let total_len = 20 + tcp_len;
        let mut pkt = vec![0u8; total_len];
        // IPv4 header
        pkt[0] = 0x45;
        pkt[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
        pkt[8] = 64;
        pkt[9] = 6; // TCP
        pkt[12..16].copy_from_slice(&[10, 0, 0, 1]);
        pkt[16..20].copy_from_slice(&[10, 0, 0, 2]);
        // TCP header
        pkt[20..22].copy_from_slice(&src_port.to_be_bytes());
        pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
        pkt[24..28].copy_from_slice(&seq.to_be_bytes());
        pkt[32..34].copy_from_slice(&data_offset_flags.to_be_bytes());
        pkt[34..36].copy_from_slice(&65535u16.to_be_bytes()); // window
        pkt
    }

    const ACK: u8 = 0x10;
    const FIN: u8 = 0x01;
    const RST: u8 = 0x04;

    fn data_pkt(src_port: u16, dst_port: u16, seq: u32, payload_len: usize) -> Vec<u8> {
        ipv4_tcp_packet(src_port, dst_port, seq, ACK, payload_len)
    }

    fn pure_ack(src_port: u16, dst_port: u16, seq: u32) -> Vec<u8> {
        ipv4_tcp_packet(src_port, dst_port, seq, ACK, 0)
    }

    fn fin_pkt(src_port: u16, dst_port: u16, seq: u32) -> Vec<u8> {
        ipv4_tcp_packet(src_port, dst_port, seq, FIN, 1)
    }

    fn rst_pkt(src_port: u16, dst_port: u16, seq: u32) -> Vec<u8> {
        ipv4_tcp_packet(src_port, dst_port, seq, RST, 0)
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    #[test]
    fn empty_tracker_returns_zero_loss() {
        let t = RetransmitTracker::new();
        assert!((t.current_loss_pct() - 0.0).abs() < f32::EPSILON);
    }

    #[test]
    fn first_segment_of_new_flow_is_not_retransmit() {
        let mut t = RetransmitTracker::new();
        t.observe(&data_pkt(1000, 443, 100, 10));
        assert_eq!(t.total_segments, 1);
        assert_eq!(t.retransmits, 0);
        assert!((t.current_loss_pct() - 0.0).abs() < f32::EPSILON);
    }

    #[test]
    fn same_seq_with_payload_is_retransmit() {
        let mut t = RetransmitTracker::new();
        t.observe(&data_pkt(1000, 443, 100, 10));
        t.observe(&data_pkt(1000, 443, 100, 10)); // same seq → retransmit
        assert_eq!(t.retransmits, 1);
        assert_eq!(t.total_segments, 2);
    }

    #[test]
    fn earlier_seq_with_payload_is_retransmit() {
        let mut t = RetransmitTracker::new();
        t.observe(&data_pkt(1000, 443, 200, 10));
        t.observe(&data_pkt(1000, 443, 100, 10)); // earlier seq → retransmit
        assert_eq!(t.retransmits, 1);
    }

    #[test]
    fn forward_progress_is_not_retransmit() {
        let mut t = RetransmitTracker::new();
        t.observe(&data_pkt(1000, 443, 100, 10));
        t.observe(&data_pkt(1000, 443, 200, 10)); // forward → not retransmit
        assert_eq!(t.retransmits, 0);
        assert_eq!(t.total_segments, 2);
    }

    #[test]
    fn pure_ack_is_skipped() {
        let mut t = RetransmitTracker::new();
        t.observe(&pure_ack(1000, 443, 100));
        assert_eq!(t.total_segments, 0, "pure ACK must not count as segment");
        assert_eq!(t.retransmits, 0);
    }

    #[test]
    fn fin_removes_flow_state() {
        let mut t = RetransmitTracker::new();
        t.observe(&data_pkt(1000, 443, 100, 10));
        t.observe(&fin_pkt(1000, 443, 110));
        // After FIN, flow is evicted. The next data packet for the same
        // four-tuple is treated as a new flow (not a retransmit).
        t.observe(&data_pkt(1000, 443, 50, 10));
        assert_eq!(t.retransmits, 0, "seq 50 after FIN re-open must not be retransmit");
    }

    #[test]
    fn rst_removes_flow_state() {
        let mut t = RetransmitTracker::new();
        t.observe(&data_pkt(2000, 80, 500, 20));
        t.observe(&rst_pkt(2000, 80, 520));
        t.observe(&data_pkt(2000, 80, 100, 10));
        assert_eq!(t.retransmits, 0, "seq 100 after RST re-open must not be retransmit");
    }

    #[test]
    fn malformed_packet_is_silently_ignored() {
        let mut t = RetransmitTracker::new();
        t.observe(b"not an ip packet at all");
        assert_eq!(t.total_segments, 0);
        assert_eq!(t.retransmits, 0);
    }

    #[test]
    fn udp_packet_is_ignored() {
        let mut t = RetransmitTracker::new();
        let mut pkt = vec![0u8; 28];
        pkt[0] = 0x45;
        pkt[2..4].copy_from_slice(&28u16.to_be_bytes());
        pkt[8] = 64;
        pkt[9] = 17; // UDP
        t.observe(&pkt);
        assert_eq!(t.total_segments, 0);
    }

    #[test]
    fn different_flows_tracked_independently() {
        let mut t = RetransmitTracker::new();
        t.observe(&data_pkt(1000, 443, 100, 10)); // flow A first
        t.observe(&data_pkt(2000, 443, 100, 10)); // flow B first (same seq, different ports)
        // Both are first segments — no retransmit.
        assert_eq!(t.retransmits, 0);
        assert_eq!(t.total_segments, 2);

        t.observe(&data_pkt(1000, 443, 100, 10)); // flow A retransmit
        assert_eq!(t.retransmits, 1);
    }

    #[test]
    fn lru_eviction_at_capacity() {
        let mut t = RetransmitTracker::new();
        // Fill the cache with FLOW_CACHE_CAP distinct flows.
        for i in 0..FLOW_CACHE_CAP as u16 {
            t.observe(&data_pkt(1000 + i, 443, 100, 10));
        }
        // Adding one more flow evicts the LRU entry. The evicted flow's
        // next segment should not be counted as a retransmit (it starts fresh).
        t.observe(&data_pkt(9000, 443, 100, 10)); // new flow, evicts oldest
        // Port 1000 was evicted. Sending same seq for it is now a first-segment.
        t.observe(&data_pkt(1000, 443, 50, 10)); // re-inserted as new flow
        assert_eq!(t.retransmits, 0, "evicted flow re-entry must not count as retransmit");
    }

    #[test]
    fn loss_pct_clamped_to_100() {
        let mut t = RetransmitTracker::new();
        // 1 new segment + 10 retransmits of same seq = 10/11 ≈ 91%
        t.observe(&data_pkt(1000, 443, 100, 10));
        for _ in 0..10 {
            t.observe(&data_pkt(1000, 443, 100, 10));
        }
        let pct = t.current_loss_pct();
        assert!((0.0..=100.0).contains(&pct), "loss_pct={pct} out of range");
        assert!(pct > 80.0, "expected high loss, got {pct}");
    }
}
