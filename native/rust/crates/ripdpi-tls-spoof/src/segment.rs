//! Pure IPv4+TCP segment byte construction for the decoy ClientHello injection.
//!
//! This module is **target-agnostic** (no `#[cfg(target_os)]` gate): the byte
//! construction and per-method corruption logic are the same on Linux and
//! macOS, and are fully unit-tested on both. Only the syscall that *sends* the
//! built bytes is Linux-gated (see [`crate::inject`]).
//!
//! ## Byte layout produced by [`build_spoof_segment`]
//!
//! ```text
//! [IPv4 header — 20 bytes]
//!   version/IHL, DSCP/ECN, total_length, id, flags/frag_off, TTL,
//!   proto=6 (TCP), header_checksum, src_ip, dst_ip
//! [TCP header — 20 bytes + options]
//!   src_port, dst_port, seq (u32), ack (u32), data_offset/flags,
//!   window, checksum (over IPv4 pseudo-header), urg_ptr
//!   [TCP options — present when method requires them]
//! [payload — the forged ClientHello bytes]
//! ```
//!
//! The TCP checksum covers the IPv4 pseudo-header
//! `(src_ip, dst_ip, 0, proto=6, tcp_segment_len)` as specified in RFC 793.
//! `etherparse::TcpHeader::calc_checksum_ipv4_raw` is used for that.
//!
//! ## Per-method corruption
//!
//! | Method | Corrupted field | Rule |
//! |---|---|---|
//! | `WrongAck` | TCP ACK number | `real_ack.wrapping_add(WRONG_ACK_DELTA)` (0xDEAD_BEEF offset; out of peer window) |
//! | `WrongMd5` | TCP options | Kind=19 (RFC 2385), Len=18, 16-byte deterministic-from-seq bogus digest (server drops on auth mismatch) |
//! | `WrongTimestamp` | TCP options | Kind=8 (RFC 7323), Len=10, TSval = live `TCP_TIMESTAMP` − [`STALE_TS_DELTA`] (PAWS: server drops as an old retransmission) |

//! For `WrongTimestamp` the injected TSval must be **older** than every
//! timestamp the peer has seen on this connection: PAWS (RFC 7323 §5.3)
//! discards segments whose TSval is less than the receiver's stored
//! `TS.Recent` — a future value such as `u32::MAX` would instead be accepted
//! and adopted as the new `TS.Recent`, permanently poisoning the connection.
//!
//! For `WrongMd5` and `WrongTimestamp` the ACK field carries `real_ack` — the
//! corruption is in the options. For `WrongAck` there are no corrupted options.
//! The TCP checksum is always computed *after* the corruption is applied, so the
//! checksum is consistent with the (corrupted) fields — the DPI box validates it
//! and parses the SNI, while the real server drops on the semantic mismatch.

use std::net::{Ipv4Addr, SocketAddrV4};

use etherparse::{Ipv4Header, TcpHeader, ip_number};

use crate::config::SpoofMethod;

/// Delta added to the real ACK number for `WrongAck` corruption.
///
/// `0xDEAD_BEEF` places the spoofed ACK far outside the peer's receive window
/// (typical window ≤ 65535) so the real server drops the segment, while the
/// DPI box still parses the ClientHello payload.
pub const WRONG_ACK_DELTA: u32 = 0xDEAD_BEEF;

/// How far behind the live TCP clock the decoy's TSval sits for
/// `WrongTimestamp`, in Linux timestamp ticks (~1 ms each).
///
/// PAWS (RFC 7323 §5.3) discards segments whose TSval is *older* than the
/// receiver's stored `TS.Recent`. The server's `TS.Recent` comes from our SYN
/// and prior segments, so a TSval one million ticks (~16 minutes) behind the
/// live clock is guaranteed older than anything seen on this connection while
/// staying well inside the 24-day `ts_recent` freshness window past which
/// PAWS checks are skipped entirely.
pub const STALE_TS_DELTA: u32 = 1_000_000;

/// Parameters for building one corrupted decoy TCP segment.
#[derive(Debug, Clone)]
pub struct SpoofSegmentParams {
    /// IPv4 source address (the relay's address on the upstream connection).
    pub src_ip: Ipv4Addr,
    /// TCP source port (the relay's ephemeral port).
    pub src_port: u16,
    /// IPv4 destination address (the real upstream server).
    pub dst_ip: Ipv4Addr,
    /// TCP destination port (typically 443).
    pub dst_port: u16,
    /// The live TCP send-sequence number read via `TCP_REPAIR` / `TCP_QUEUE_SEQ`
    /// from the established connection. The forged segment uses this so it is
    /// in-window for the DPI box's TCP state machine.
    pub seq: u32,
    /// The live TCP ACK number from the same `TCP_REPAIR` snapshot. For
    /// `WrongAck` this is offset by [`WRONG_ACK_DELTA`]; for all other methods
    /// it is used verbatim.
    pub ack: u32,
    /// TCP receive-window size from the snapshot.
    pub window: u16,
    /// IP TTL. Use the live socket TTL to match the connection's fingerprint.
    pub ttl: u8,
    /// IPv4 identification field (arbitrary; caller may use time-derived value).
    pub ip_id: u16,
    /// The forged ClientHello bytes (already SNI-replaced). Becomes the TCP
    /// payload of the decoy segment.
    pub payload: Vec<u8>,
    /// Corruption method. Determines which field or option is sabotaged.
    pub method: SpoofMethod,
    /// Stale TCP timestamp value (live `TCP_TIMESTAMP` − [`STALE_TS_DELTA`])
    /// for the `WrongTimestamp` decoy's TS option. Must be older than every
    /// TSval the peer has seen on this connection so PAWS discards the
    /// segment. `None` means timestamps were not negotiated (or could not be
    /// read); `WrongTimestamp` then fails closed instead of emitting a
    /// segment the server would accept.
    pub tsval: Option<u32>,
}

/// Build a single IPv4+TCP segment carrying the forged decoy ClientHello.
///
/// The segment is corrupted per `params.method` (see module-level table) so
/// the real server drops it while the DPI box parses the SNI. The TCP checksum
/// is computed correctly over the IPv4 pseudo-header — both the DPI box and the
/// server verify it; a bad checksum would cause the DPI box to discard the
/// segment and miss the decoy SNI.
///
/// Returns the complete on-wire bytes: IPv4 header + TCP header (+options) +
/// payload. Suitable for `sendto` on a `SOCK_RAW / IPPROTO_RAW` socket with
/// `IP_HDRINCL` set.
///
/// Errors if `etherparse` rejects a parameter (e.g. payload too large for
/// IPv4 `u16` total-length field, or TCP options overflow the 40-byte option
/// space). These are returned as `std::io::Error` for consistency with the
/// caller's send path.
pub fn build_spoof_segment(params: &SpoofSegmentParams) -> std::io::Result<Vec<u8>> {
    let src = SocketAddrV4::new(params.src_ip, params.src_port);
    let dst = SocketAddrV4::new(params.dst_ip, params.dst_port);

    // --- TCP header ---
    let ack = match params.method {
        SpoofMethod::WrongAck => params.ack.wrapping_add(WRONG_ACK_DELTA),
        SpoofMethod::WrongMd5 | SpoofMethod::WrongTimestamp => params.ack,
    };

    let mut tcp = TcpHeader::new(params.src_port, params.dst_port, params.seq, params.window);
    tcp.ack = true;
    tcp.psh = !params.payload.is_empty();
    tcp.acknowledgment_number = ack;

    // Build TCP options according to method.
    let raw_opts = match params.method {
        SpoofMethod::WrongAck => {
            // No options — the ACK-field corruption is the only sabotage.
            Vec::new()
        }
        SpoofMethod::WrongMd5 => build_wrong_md5_options(params.seq),
        SpoofMethod::WrongTimestamp => {
            let tsval = params.tsval.ok_or_else(|| {
                std::io::Error::new(
                    std::io::ErrorKind::InvalidInput,
                    "WrongTimestamp requires negotiated TCP timestamps (no stale tsval available)",
                )
            })?;
            build_wrong_timestamp_options(tsval)
        }
    };

    if !raw_opts.is_empty() {
        tcp.set_options_raw(&raw_opts)
            .map_err(|_| std::io::Error::new(std::io::ErrorKind::InvalidInput, "TCP options too large"))?;
    }

    // TCP checksum over IPv4 pseudo-header — must be valid so the DPI box
    // parses the segment rather than discarding it as malformed.
    tcp.checksum = tcp
        .calc_checksum_ipv4_raw(src.ip().octets(), dst.ip().octets(), &params.payload)
        .map_err(|_| std::io::Error::new(std::io::ErrorKind::InvalidInput, "TCP checksum: payload too large"))?;

    // --- IPv4 header ---
    let tcp_segment_len = u16::try_from(tcp.header_len() + params.payload.len()).map_err(|_| {
        std::io::Error::new(std::io::ErrorKind::InvalidInput, "TCP segment exceeds IPv4 total-length field")
    })?;
    let mut ip = Ipv4Header::new(tcp_segment_len, params.ttl, ip_number::TCP, src.ip().octets(), dst.ip().octets())
        .map_err(|_| std::io::Error::new(std::io::ErrorKind::InvalidInput, "IPv4 header construction failed"))?;
    ip.identification = params.ip_id;
    ip.dont_fragment = false;
    ip.more_fragments = false;
    ip.header_checksum = ip.calc_header_checksum();

    // --- Assemble the packet ---
    let mut packet = Vec::with_capacity(Ipv4Header::MIN_LEN + tcp.header_len() + params.payload.len());
    ip.write(&mut packet).map_err(std::io::Error::other)?;
    tcp.write(&mut packet).map_err(std::io::Error::other)?;
    packet.extend_from_slice(&params.payload);
    Ok(packet)
}

/// Build the raw TCP options bytes for `WrongMd5`.
///
/// Layout (RFC 2385 §2.7 + standard TCP option framing):
/// ```text
/// [01]           Noop  (alignment padding)
/// [01]           Noop
/// [13] [12]      Kind=19, Len=18
/// [xx xx … x16]  16-byte deterministic-from-seq "signature"
/// [00]           EOL padding to 4-byte boundary
/// ```
/// Total: 20 bytes (5 groups of 4), fits in the 40-byte option space.
///
/// The 16-byte digest is deterministic (derived from `seq`) so tests can assert
/// it is non-zero and reproducible, without needing a real HMAC-MD5 call. The
/// server does not need to actually reject it based on content — any non-empty
/// MD5 option causes a server not configured for TCP MD5 to discard the segment.
fn build_wrong_md5_options(seq: u32) -> Vec<u8> {
    let mut opts = Vec::with_capacity(20);
    opts.push(1); // Noop
    opts.push(1); // Noop
    opts.push(19); // Kind = TCP-MD5 Signature
    opts.push(18); // Length = 18 (2-byte header + 16-byte digest)
    // 16-byte deterministic digest seeded from seq (Knuth multiplicative hash
    // applied four times). Not a valid MD5 — just non-zero and reproducible.
    for i in 0u32..4 {
        opts.extend_from_slice(&seq.wrapping_add(i).wrapping_mul(2_654_435_761).to_be_bytes());
    }
    // Pad to 4-byte boundary: 2 (noops) + 18 (md5 option) = 20 bytes — already
    // a multiple of 4, no EOL padding needed.
    debug_assert_eq!(opts.len(), 20);
    debug_assert!(opts.len().is_multiple_of(4));
    opts
}

/// Build the raw TCP options bytes for `WrongTimestamp`.
///
/// Layout (RFC 7323 §3 + standard TCP option framing):
/// ```text
/// [01] [01]      2x Noop (alignment)
/// [08] [0A]      Kind=8, Len=10
/// [xx xx xx xx]  TSval = live_ts - STALE_TS_DELTA  (older than TS.Recent)
/// [00 00 00 00]  TSecr = 0
/// ```
/// Total: 12 bytes (3 groups of 4).
///
/// PAWS (RFC 7323 §5.3) discards segments whose TSval is *older* than the
/// receiver's stored `TS.Recent`; `tsval` must therefore be a stale value
/// (see [`STALE_TS_DELTA`]). A future value would be accepted and adopted as
/// the new `TS.Recent`, permanently poisoning the connection.
fn build_wrong_timestamp_options(tsval: u32) -> Vec<u8> {
    let mut opts = Vec::with_capacity(12);
    opts.push(1); // Noop
    opts.push(1); // Noop
    opts.push(8); // Kind = Timestamp
    opts.push(10); // Length = 10
    opts.extend_from_slice(&tsval.to_be_bytes()); // TSval — older than TS.Recent
    opts.extend_from_slice(&0u32.to_be_bytes()); // TSecr
    debug_assert_eq!(opts.len(), 12);
    debug_assert!(opts.len().is_multiple_of(4));
    opts
}

/// Re-parse the TCP header from the assembled packet bytes and verify the
/// checksum is consistent with the given pseudo-header parameters.
///
/// `ip_hdr_len` is the IPv4 header length (typically `Ipv4Header::MIN_LEN`).
/// Returns the parsed ACK field so callers can assert corruption was applied.
///
/// This is a test-support function exported for use in unit tests.
#[must_use]
pub fn verify_checksum_from_packet(packet: &[u8], src_ip: [u8; 4], dst_ip: [u8; 4], payload_len: usize) -> bool {
    let ip_len = Ipv4Header::MIN_LEN;
    let Some(tcp_slice) = packet.get(ip_len..) else { return false };
    let Ok(header) = etherparse::TcpHeaderSlice::from_slice(tcp_slice) else { return false };
    let payload_start = packet.len().saturating_sub(payload_len);
    let Some(payload) = packet.get(payload_start..) else { return false };
    let Ok(expected) = header.calc_checksum_ipv4_raw(src_ip, dst_ip, payload) else { return false };
    // The stored checksum is at bytes [ip_len+16 .. ip_len+18] of the packet.
    let Some(stored_bytes) = packet.get(ip_len + 16..ip_len + 18) else { return false };
    let stored = u16::from_be_bytes([stored_bytes[0], stored_bytes[1]]);
    stored == expected
}

/// Read the TCP ACK field from an assembled packet (after the IPv4 header).
#[must_use]
pub fn read_tcp_ack(packet: &[u8]) -> Option<u32> {
    let ip_len = Ipv4Header::MIN_LEN;
    let bytes = packet.get(ip_len + 8..ip_len + 12)?;
    Some(u32::from_be_bytes([bytes[0], bytes[1], bytes[2], bytes[3]]))
}

/// Read a TCP option (kind, len, data) from the options region of an assembled
/// packet, returning the offset of the first occurrence of `kind`, or `None`.
#[must_use]
pub fn find_tcp_option_in_packet(packet: &[u8], kind: u8) -> Option<usize> {
    let ip_len = Ipv4Header::MIN_LEN;
    let tcp_start = ip_len;
    // Data offset is in the high nibble of byte 12 of the TCP header, in units of 4.
    let data_off_byte = *packet.get(tcp_start + 12)?;
    let data_offset = ((data_off_byte >> 4) as usize) * 4;
    let opts_start = tcp_start + 20;
    let opts_end = tcp_start + data_offset;
    if opts_end > packet.len() || opts_start >= opts_end {
        return None;
    }
    let opts = &packet[opts_start..opts_end];
    let mut i = 0;
    while i < opts.len() {
        match opts[i] {
            0 => break, // EOL
            1 => {
                i += 1;
            } // Noop
            k => {
                if i + 1 >= opts.len() {
                    break;
                }
                let len = opts[i + 1] as usize;
                if len < 2 || i + len > opts.len() {
                    // Malformed length: too short, or it would run past the
                    // options region. Stop rather than skip into hand-waved
                    // bytes (defensive — the inject path only ever builds
                    // well-formed options).
                    break;
                }
                if k == kind {
                    return Some(tcp_start + 20 + i);
                }
                i += len;
            }
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;

    fn base_params(method: SpoofMethod) -> SpoofSegmentParams {
        SpoofSegmentParams {
            src_ip: Ipv4Addr::new(10, 0, 0, 1),
            src_port: 54321,
            dst_ip: Ipv4Addr::new(93, 184, 216, 34), // example.com
            dst_port: 443,
            seq: 0x1000_0000,
            ack: 0x2000_0000,
            window: 65535,
            ttl: 64,
            ip_id: 0x1234,
            payload: b"GET / HTTP/1.1\r\nHost: decoy.example.com\r\n\r\n".to_vec(),
            method,
            tsval: Some(0x0200_0000),
        }
    }

    // ── WrongAck ──────────────────────────────────────────────────────────────

    #[test]
    fn wrong_ack_ack_field_corrupted() {
        let params = base_params(SpoofMethod::WrongAck);
        let pkt = build_spoof_segment(&params).unwrap();

        let got_ack = read_tcp_ack(&pkt).expect("TCP ACK present");
        let expected = params.ack.wrapping_add(WRONG_ACK_DELTA);
        assert_eq!(
            got_ack, expected,
            "WrongAck: ACK field should be real_ack + WRONG_ACK_DELTA ({WRONG_ACK_DELTA:#010X})"
        );
        // ACK must differ from the real ack.
        assert_ne!(got_ack, params.ack);
    }

    #[test]
    fn wrong_ack_no_md5_or_timestamp_option() {
        let params = base_params(SpoofMethod::WrongAck);
        let pkt = build_spoof_segment(&params).unwrap();
        assert!(find_tcp_option_in_packet(&pkt, 19).is_none(), "WrongAck must not inject Kind=19");
        assert!(find_tcp_option_in_packet(&pkt, 8).is_none(), "WrongAck must not inject Kind=8");
    }

    #[test]
    fn wrong_ack_checksum_valid() {
        let params = base_params(SpoofMethod::WrongAck);
        let pkt = build_spoof_segment(&params).unwrap();
        assert!(
            verify_checksum_from_packet(&pkt, params.src_ip.octets(), params.dst_ip.octets(), params.payload.len()),
            "WrongAck: TCP checksum must be valid over pseudo-header"
        );
    }

    #[test]
    fn wrong_ack_payload_embedded() {
        let params = base_params(SpoofMethod::WrongAck);
        let pkt = build_spoof_segment(&params).unwrap();
        let tail = &pkt[pkt.len() - params.payload.len()..];
        assert_eq!(tail, params.payload.as_slice());
    }

    // ── WrongMd5 ─────────────────────────────────────────────────────────────

    #[test]
    fn wrong_md5_option_present_kind19_len18() {
        let params = base_params(SpoofMethod::WrongMd5);
        let pkt = build_spoof_segment(&params).unwrap();

        let opt_off = find_tcp_option_in_packet(&pkt, 19).expect("Kind=19 (MD5) must be present");
        // Byte 0: kind=19; byte 1: len=18.
        assert_eq!(pkt[opt_off], 19, "kind byte");
        assert_eq!(pkt[opt_off + 1], 18, "len byte (2 header + 16 digest)");
        // The 16-byte digest must be non-zero.
        let digest = &pkt[opt_off + 2..opt_off + 18];
        assert!(digest.iter().any(|&b| b != 0), "MD5 digest bytes must be non-zero");
    }

    #[test]
    fn wrong_md5_ack_field_not_corrupted() {
        let params = base_params(SpoofMethod::WrongMd5);
        let pkt = build_spoof_segment(&params).unwrap();
        let got_ack = read_tcp_ack(&pkt).unwrap();
        assert_eq!(got_ack, params.ack, "WrongMd5: ACK field must be the real ACK value");
    }

    #[test]
    fn wrong_md5_checksum_valid() {
        let params = base_params(SpoofMethod::WrongMd5);
        let pkt = build_spoof_segment(&params).unwrap();
        assert!(
            verify_checksum_from_packet(&pkt, params.src_ip.octets(), params.dst_ip.octets(), params.payload.len()),
            "WrongMd5: TCP checksum must be valid over pseudo-header"
        );
    }

    #[test]
    fn wrong_md5_digest_is_deterministic() {
        // Same seq → same digest across two calls.
        let p1 = base_params(SpoofMethod::WrongMd5);
        let p2 = base_params(SpoofMethod::WrongMd5);
        let pkt1 = build_spoof_segment(&p1).unwrap();
        let pkt2 = build_spoof_segment(&p2).unwrap();
        let off1 = find_tcp_option_in_packet(&pkt1, 19).unwrap();
        let off2 = find_tcp_option_in_packet(&pkt2, 19).unwrap();
        assert_eq!(&pkt1[off1..off1 + 18], &pkt2[off2..off2 + 18], "digest is deterministic for same seq");

        // Different seq → different digest.
        let mut p3 = base_params(SpoofMethod::WrongMd5);
        p3.seq = p1.seq.wrapping_add(1);
        let pkt3 = build_spoof_segment(&p3).unwrap();
        let off3 = find_tcp_option_in_packet(&pkt3, 19).unwrap();
        assert_ne!(&pkt1[off1 + 2..off1 + 18], &pkt3[off3 + 2..off3 + 18], "different seq → different digest");
    }

    // ── WrongTimestamp ────────────────────────────────────────────────────────

    #[test]
    fn wrong_timestamp_option_present_kind8() {
        let params = base_params(SpoofMethod::WrongTimestamp);
        let pkt = build_spoof_segment(&params).unwrap();

        let opt_off = find_tcp_option_in_packet(&pkt, 8).expect("Kind=8 (Timestamp) must be present");
        assert_eq!(pkt[opt_off], 8, "kind byte");
        assert_eq!(pkt[opt_off + 1], 10, "len byte");
        // TSval must be the stale value from params (older than TS.Recent),
        // NOT a future value: PAWS only rejects older-than-recent timestamps.
        let tsval = u32::from_be_bytes([pkt[opt_off + 2], pkt[opt_off + 3], pkt[opt_off + 4], pkt[opt_off + 5]]);
        assert_eq!(tsval, params.tsval.unwrap(), "TSval must be the caller-supplied stale value");
        let tsecr = u32::from_be_bytes([pkt[opt_off + 6], pkt[opt_off + 7], pkt[opt_off + 8], pkt[opt_off + 9]]);
        assert_eq!(tsecr, 0, "TSecr must be zero");
    }

    #[test]
    fn wrong_timestamp_fails_closed_without_tsval() {
        // No negotiated timestamps -> no tsval -> the builder must refuse to
        // emit a segment the server would silently ACCEPT.
        let mut params = base_params(SpoofMethod::WrongTimestamp);
        params.tsval = None;
        let err = build_spoof_segment(&params).unwrap_err();
        assert_eq!(err.kind(), std::io::ErrorKind::InvalidInput);
    }

    #[test]
    fn wrong_timestamp_ack_field_not_corrupted() {
        let params = base_params(SpoofMethod::WrongTimestamp);
        let pkt = build_spoof_segment(&params).unwrap();
        let got_ack = read_tcp_ack(&pkt).unwrap();
        assert_eq!(got_ack, params.ack, "WrongTimestamp: ACK must be the real value");
    }

    #[test]
    fn wrong_timestamp_checksum_valid() {
        let params = base_params(SpoofMethod::WrongTimestamp);
        let pkt = build_spoof_segment(&params).unwrap();
        assert!(
            verify_checksum_from_packet(&pkt, params.src_ip.octets(), params.dst_ip.octets(), params.payload.len()),
            "WrongTimestamp: TCP checksum must be valid over pseudo-header"
        );
    }

    // ── Cross-method structural checks ────────────────────────────────────────

    #[test]
    fn ipv4_header_fields_correct_for_all_methods() {
        for method in [SpoofMethod::WrongAck, SpoofMethod::WrongMd5, SpoofMethod::WrongTimestamp] {
            let params = base_params(method);
            let pkt = build_spoof_segment(&params).unwrap();

            // IPv4: version=4, IHL=5 (no IP options), proto=TCP=6.
            assert_eq!(pkt[0] >> 4, 4, "{method:?}: IP version");
            assert_eq!(pkt[0] & 0x0F, 5, "{method:?}: IHL=5 (no IP options)");
            assert_eq!(pkt[9], 6, "{method:?}: protocol=TCP");
            // TTL.
            assert_eq!(pkt[8], params.ttl, "{method:?}: TTL");
            // Src/dst IP embedded.
            assert_eq!(&pkt[12..16], &params.src_ip.octets(), "{method:?}: src IP");
            assert_eq!(&pkt[16..20], &params.dst_ip.octets(), "{method:?}: dst IP");
        }
    }

    #[test]
    fn tcp_src_dst_ports_correct_for_all_methods() {
        for method in [SpoofMethod::WrongAck, SpoofMethod::WrongMd5, SpoofMethod::WrongTimestamp] {
            let params = base_params(method);
            let pkt = build_spoof_segment(&params).unwrap();
            let ip_len = Ipv4Header::MIN_LEN;
            let src_port = u16::from_be_bytes([pkt[ip_len], pkt[ip_len + 1]]);
            let dst_port = u16::from_be_bytes([pkt[ip_len + 2], pkt[ip_len + 3]]);
            assert_eq!(src_port, params.src_port, "{method:?}: src port");
            assert_eq!(dst_port, params.dst_port, "{method:?}: dst port");
        }
    }

    #[test]
    fn wrong_timestamp_no_md5_option() {
        let params = base_params(SpoofMethod::WrongTimestamp);
        let pkt = build_spoof_segment(&params).unwrap();
        assert!(find_tcp_option_in_packet(&pkt, 19).is_none(), "WrongTimestamp must not inject Kind=19");
    }

    #[test]
    fn wrong_md5_no_timestamp_option() {
        let params = base_params(SpoofMethod::WrongMd5);
        let pkt = build_spoof_segment(&params).unwrap();
        assert!(find_tcp_option_in_packet(&pkt, 8).is_none(), "WrongMd5 must not inject Kind=8");
    }
}
