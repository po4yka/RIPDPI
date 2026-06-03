use crate::Stats;

/// Extracts the QUIC Initial SNI from `payload` (if it carries a QUIC long-header
/// packet) and records it into `stats`. Called once per new UDP association.
/// Non-QUIC datagrams return immediately without any allocation.
pub(in crate::io_loop) fn record_quic_sni_if_present(stats: &Stats, payload: &[u8]) {
    // QUIC long-header: first byte has bit 7 set (0x80).
    if payload.first().is_none_or(|&b| b & 0x80 == 0) {
        return;
    }
    if let Some(info) = ripdpi_packets::parse_quic_initial(payload) {
        let host = info.host();
        if !host.is_empty()
            && let Ok(sni) = std::str::from_utf8(host)
        {
            stats.record_last_host(Some(sni));
        }
    }
}
