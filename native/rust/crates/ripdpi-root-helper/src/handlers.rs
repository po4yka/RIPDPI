mod capabilities;
mod fake_tcp_handlers;
mod fd_adoption;
mod privileged_adapters;
mod tcp_fragment_handlers;
mod udp_fragment_handlers;

pub use capabilities::handle_probe_capabilities;
pub use fake_tcp_handlers::{
    handle_send_fake_rst, handle_send_fake_tcp, handle_send_flagged_tcp_payload, handle_send_multi_disorder_tcp,
    handle_send_ordered_tcp_segments, handle_send_seqovl_tcp,
};
pub use privileged_adapters::{
    handle_recv_icmp_wrapped_udp, handle_send_icmp_wrapped_udp, handle_send_raw_ip_packet, handle_send_syn_hide_tcp,
};
pub use tcp_fragment_handlers::handle_send_ip_fragmented_tcp;
pub use udp_fragment_handlers::handle_send_ip_fragmented_udp;

// The helper serves one request at a time, so a client-chosen wait blocks every
// other command, nfqws2 supervision and shutdown. These bounds keep each wait short.
const MAX_WAIT_POLL_MS: u64 = 1_000;
const MAX_INTER_SEGMENT_DELAY_TOTAL_MS: u32 = 1_000;
const MAX_ICMP_RECV_TIMEOUT_MS: u64 = 5_000;

/// The poll interval of a TCP-stage wait, at least 1 ms and at most 1 s.
fn bounded_wait_poll(wait_poll_ms: u64) -> std::time::Duration {
    std::time::Duration::from_millis(wait_poll_ms.clamp(1, MAX_WAIT_POLL_MS))
}

/// The delay between `segments` raw packets, so that all gaps together take at most 1 s.
fn bounded_inter_segment_delay_ms(delay_ms: u32, segments: usize) -> u32 {
    let gaps = u32::try_from(segments.saturating_sub(1).max(1)).unwrap_or(u32::MAX);
    delay_ms.min(MAX_INTER_SEGMENT_DELAY_TOTAL_MS / gaps)
}

/// The receive timeout of an ICMP-wrapped UDP read, at most 5 s.
fn bounded_icmp_recv_timeout_ms(timeout_ms: u64) -> u64 {
    timeout_ms.min(MAX_ICMP_RECV_TIMEOUT_MS)
}

#[cfg(test)]
mod tests {
    use std::time::Duration;

    use super::{bounded_icmp_recv_timeout_ms, bounded_inter_segment_delay_ms, bounded_wait_poll};

    #[test]
    fn client_chosen_waits_are_bounded() {
        assert_eq!(bounded_wait_poll(1_000_000_000_000), Duration::from_secs(1));
        assert_eq!(bounded_wait_poll(0), Duration::from_millis(1));
        assert_eq!(bounded_wait_poll(10), Duration::from_millis(10));

        // Three segments have two gaps: 2 x 500 ms is the 1 s total.
        assert_eq!(bounded_inter_segment_delay_ms(u32::MAX, 3), 500);
        assert_eq!(bounded_inter_segment_delay_ms(5, 3), 5);
        assert_eq!(bounded_inter_segment_delay_ms(0, 3), 0);
        assert_eq!(bounded_inter_segment_delay_ms(10, 1_000), 1);
        assert_eq!(bounded_inter_segment_delay_ms(10, 0), 10);

        assert_eq!(bounded_icmp_recv_timeout_ms(600_000), 5_000);
        assert_eq!(bounded_icmp_recv_timeout_ms(1_000), 1_000);
    }
}
