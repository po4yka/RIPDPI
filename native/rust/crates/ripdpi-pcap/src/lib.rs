#![forbid(unsafe_code)]

//! Classic libpcap (LINKTYPE_RAW) writer + reader + endpoint-redaction
//! for the RIPDPI PCAP-export subsystem.
//!
//! Format: classic pcap with `0xa1b2c3d4` magic, version 2.4, LINKTYPE_RAW
//! (101). The TUN device yields bare IPv4 / IPv6 packets - no Ethernet
//! frame - so the raw linktype matches the wire shape exactly.
//!
//! Wireshark / tshark / tcpdump open the output without arguments.

mod constants;
mod reader;
mod redact;
mod writer;

pub use constants::{LINKTYPE_RAW, MAGIC, SNAPLEN_DEFAULT};
pub use reader::{PcapReader, PcapRecord};
pub use redact::{redact_in_place, rewrite_endpoints};
pub use writer::PcapWriter;

#[cfg(test)]
mod tests {
    use std::io::Cursor;

    use proptest::prelude::*;

    use super::*;

    // -- helpers -----------------------------------------------------------

    /// Construct a minimal IPv4 + TCP packet with the given src/dst and
    /// payload. Lengths/checksums are filled in. Returns the bytes.
    fn make_ipv4_tcp(src: [u8; 4], dst: [u8; 4], payload: &[u8]) -> Vec<u8> {
        let ip_hdr_len = 20;
        let tcp_hdr_len = 20;
        let total_len = ip_hdr_len + tcp_hdr_len + payload.len();
        let mut packet = vec![0u8; total_len];
        // IPv4 header
        packet[0] = 0x45; // version 4, IHL 5
        packet[2..4].copy_from_slice(&(total_len as u16).to_be_bytes());
        packet[8] = 64; // TTL
        packet[9] = 6; // protocol = TCP
        packet[12..16].copy_from_slice(&src);
        packet[16..20].copy_from_slice(&dst);
        // IPv4 checksum
        let mut sum: u32 = 0;
        let mut i = 0;
        while i + 1 < ip_hdr_len {
            sum = sum.wrapping_add(u32::from(u16::from_be_bytes([packet[i], packet[i + 1]])));
            i += 2;
        }
        while (sum >> 16) != 0 {
            sum = (sum & 0xffff) + (sum >> 16);
        }
        let checksum = !(sum as u16);
        packet[10..12].copy_from_slice(&checksum.to_be_bytes());
        // TCP header: source port 0x1234, dest port 0x5678, dummy checksum
        packet[ip_hdr_len] = 0x12;
        packet[ip_hdr_len + 1] = 0x34;
        packet[ip_hdr_len + 2] = 0x56;
        packet[ip_hdr_len + 3] = 0x78;
        packet[ip_hdr_len + 12] = 0x50; // data offset = 5 * 4
        packet[ip_hdr_len + 16] = 0xaa; // bogus checksum (will be zeroed)
        packet[ip_hdr_len + 17] = 0xbb;
        // payload
        packet[ip_hdr_len + tcp_hdr_len..].copy_from_slice(payload);
        packet
    }

    fn make_ipv6_udp(src: [u8; 16], dst: [u8; 16], payload: &[u8]) -> Vec<u8> {
        let ip_hdr_len = 40;
        let udp_hdr_len = 8;
        let total_len = ip_hdr_len + udp_hdr_len + payload.len();
        let mut packet = vec![0u8; total_len];
        // IPv6 header
        packet[0] = 0x60; // version 6
        let payload_len = (udp_hdr_len + payload.len()) as u16;
        packet[4..6].copy_from_slice(&payload_len.to_be_bytes());
        packet[6] = 17; // next header = UDP
        packet[7] = 64; // hop limit
        packet[8..24].copy_from_slice(&src);
        packet[24..40].copy_from_slice(&dst);
        // UDP header: src port, dst port, length, checksum
        packet[ip_hdr_len] = 0x12;
        packet[ip_hdr_len + 1] = 0x34;
        packet[ip_hdr_len + 2] = 0x56;
        packet[ip_hdr_len + 3] = 0x78;
        packet[ip_hdr_len + 4..ip_hdr_len + 6].copy_from_slice(&((udp_hdr_len + payload.len()) as u16).to_be_bytes());
        packet[ip_hdr_len + 6] = 0xcc; // bogus checksum (will be zeroed)
        packet[ip_hdr_len + 7] = 0xdd;
        packet[ip_hdr_len + udp_hdr_len..].copy_from_slice(payload);
        packet
    }

    /// Build a minimal TLS ClientHello record carrying a single SNI
    /// `host_name` extension with the given hostname. All length fields
    /// are computed so a strict parser would accept it.
    fn make_client_hello_with_sni(sni: &str) -> Vec<u8> {
        let host = sni.as_bytes();
        // server_name extension body: list_len(2) + name_type(1) + name_len(2) + name.
        let mut sni_ext_body = Vec::new();
        let entry_len = 1 + 2 + host.len();
        sni_ext_body.extend_from_slice(&(entry_len as u16).to_be_bytes()); // ServerNameList length
        sni_ext_body.push(0); // name_type = host_name
        sni_ext_body.extend_from_slice(&(host.len() as u16).to_be_bytes());
        sni_ext_body.extend_from_slice(host);

        // extension: type(2)=0x0000 + len(2) + body.
        let mut extensions = Vec::new();
        extensions.extend_from_slice(&0x0000u16.to_be_bytes());
        extensions.extend_from_slice(&(sni_ext_body.len() as u16).to_be_bytes());
        extensions.extend_from_slice(&sni_ext_body);

        // ClientHello body.
        let mut body = Vec::new();
        body.extend_from_slice(&[0x03, 0x03]); // legacy_version TLS 1.2
        body.extend_from_slice(&[0x11u8; 32]); // random
        body.push(0); // session_id length = 0
        body.extend_from_slice(&2u16.to_be_bytes()); // cipher_suites length
        body.extend_from_slice(&[0x13, 0x01]); // one cipher suite
        body.push(1); // compression_methods length
        body.push(0); // null compression
        body.extend_from_slice(&(extensions.len() as u16).to_be_bytes());
        body.extend_from_slice(&extensions);

        // Handshake header: type(1)=0x01 + length(3).
        let mut handshake = Vec::new();
        handshake.push(0x01);
        let hs_len = body.len();
        handshake.push(((hs_len >> 16) & 0xff) as u8);
        handshake.push(((hs_len >> 8) & 0xff) as u8);
        handshake.push((hs_len & 0xff) as u8);
        handshake.extend_from_slice(&body);

        // TLS record header: type(1)=0x16 + version(2) + length(2).
        let mut record = Vec::new();
        record.push(0x16);
        record.extend_from_slice(&[0x03, 0x01]); // record version TLS 1.0
        record.extend_from_slice(&(handshake.len() as u16).to_be_bytes());
        record.extend_from_slice(&handshake);
        record
    }

    /// Verify an IPv4 header's checksum is valid (sum-of-words == 0xffff).
    fn ipv4_header_checksum_valid(bytes: &[u8]) -> bool {
        let ihl = (bytes[0] & 0x0f) as usize * 4;
        if bytes.len() < ihl {
            return false;
        }
        let mut sum: u32 = 0;
        let mut i = 0;
        while i + 1 < ihl {
            sum = sum.wrapping_add(u32::from(u16::from_be_bytes([bytes[i], bytes[i + 1]])));
            i += 2;
        }
        while (sum >> 16) != 0 {
            sum = (sum & 0xffff) + (sum >> 16);
        }
        sum == 0xffff
    }

    // -- writer ------------------------------------------------------------

    #[test]
    fn writer_emits_24_byte_global_header_with_correct_magic() {
        let mut buf = Vec::new();
        {
            let _writer = PcapWriter::new(&mut buf, SNAPLEN_DEFAULT).unwrap();
        }
        assert_eq!(buf.len(), 24);
        assert_eq!(&buf[0..4], &MAGIC.to_le_bytes());
        assert_eq!(&buf[4..6], &2u16.to_le_bytes());
        assert_eq!(&buf[6..8], &4u16.to_le_bytes());
        assert_eq!(&buf[8..16], &[0u8; 8]);
        assert_eq!(&buf[16..20], &SNAPLEN_DEFAULT.to_le_bytes());
        assert_eq!(&buf[20..24], &LINKTYPE_RAW.to_le_bytes());
    }

    // -- roundtrip ---------------------------------------------------------

    #[test]
    fn writer_then_reader_roundtrips_single_packet() {
        let packet = make_ipv4_tcp([10, 0, 0, 1], [10, 0, 0, 2], b"hello");
        let mut buf = Vec::new();
        {
            let mut writer = PcapWriter::new(&mut buf, SNAPLEN_DEFAULT).unwrap();
            writer.write_packet(1_700_000_000_123_456, &packet).unwrap();
            writer.flush().unwrap();
        }
        let mut reader = PcapReader::new(Cursor::new(&buf)).unwrap();
        let record = reader.next_record().unwrap().expect("one record");
        assert_eq!(record.ts_micros, 1_700_000_000_123_456);
        assert_eq!(record.orig_len, packet.len() as u32);
        assert_eq!(record.bytes, packet);
        assert!(reader.next_record().unwrap().is_none());
    }

    #[test]
    fn writer_then_reader_roundtrips_multiple_packets() {
        let p1 = make_ipv4_tcp([1, 1, 1, 1], [2, 2, 2, 2], b"a");
        let p2 = make_ipv4_tcp([3, 3, 3, 3], [4, 4, 4, 4], b"bb");
        let p3 = make_ipv4_tcp([5, 5, 5, 5], [6, 6, 6, 6], b"ccc");
        let mut buf = Vec::new();
        {
            let mut writer = PcapWriter::new(&mut buf, SNAPLEN_DEFAULT).unwrap();
            writer.write_packet(1_000_000, &p1).unwrap();
            writer.write_packet(2_000_000, &p2).unwrap();
            writer.write_packet(3_500_000, &p3).unwrap();
        }
        let mut reader = PcapReader::new(Cursor::new(&buf)).unwrap();
        let r1 = reader.next_record().unwrap().unwrap();
        let r2 = reader.next_record().unwrap().unwrap();
        let r3 = reader.next_record().unwrap().unwrap();
        assert_eq!(r1.ts_micros, 1_000_000);
        assert_eq!(r1.bytes, p1);
        assert_eq!(r2.ts_micros, 2_000_000);
        assert_eq!(r2.bytes, p2);
        assert_eq!(r3.ts_micros, 3_500_000);
        assert_eq!(r3.bytes, p3);
        assert!(reader.next_record().unwrap().is_none());
    }

    // -- reader validation -------------------------------------------------

    #[test]
    fn reader_rejects_wrong_magic() {
        let mut header = [0u8; 24];
        header[0..4].copy_from_slice(&0xdead_beef_u32.to_le_bytes());
        header[20..24].copy_from_slice(&LINKTYPE_RAW.to_le_bytes());
        match PcapReader::new(Cursor::new(&header[..])) {
            Err(e) => assert_eq!(e.kind(), std::io::ErrorKind::InvalidData),
            Ok(_) => panic!("expected magic-mismatch error"),
        }
    }

    #[test]
    fn reader_rejects_wrong_linktype() {
        let mut header = [0u8; 24];
        header[0..4].copy_from_slice(&MAGIC.to_le_bytes());
        header[4..6].copy_from_slice(&2u16.to_le_bytes());
        header[6..8].copy_from_slice(&4u16.to_le_bytes());
        header[16..20].copy_from_slice(&SNAPLEN_DEFAULT.to_le_bytes());
        // linktype = 1 (Ethernet), not 101 (RAW)
        header[20..24].copy_from_slice(&1u32.to_le_bytes());
        match PcapReader::new(Cursor::new(&header[..])) {
            Err(e) => assert_eq!(e.kind(), std::io::ErrorKind::InvalidData),
            Ok(_) => panic!("expected linktype-mismatch error"),
        }
    }

    #[test]
    fn reader_tolerates_truncated_tail() {
        let p1 = make_ipv4_tcp([10, 0, 0, 1], [10, 0, 0, 2], b"first");
        let p2 = make_ipv4_tcp([10, 0, 0, 3], [10, 0, 0, 4], b"second");
        let mut buf = Vec::new();
        {
            let mut writer = PcapWriter::new(&mut buf, SNAPLEN_DEFAULT).unwrap();
            writer.write_packet(1_000_000, &p1).unwrap();
            writer.write_packet(2_000_000, &p2).unwrap();
        }
        // Truncate mid second record: keep first packet entirely + the
        // 16-byte header of p2 + half of p2's body.
        let p1_total = 16 + p1.len();
        let truncate_at = 24 + p1_total + 16 + (p2.len() / 2);
        buf.truncate(truncate_at);
        let mut reader = PcapReader::new(Cursor::new(&buf)).unwrap();
        let r1 = reader.next_record().unwrap().expect("first record present");
        assert_eq!(r1.bytes, p1);
        // Second record body is truncated -> read_exact returns
        // UnexpectedEof -> we return Ok(None).
        assert!(reader.next_record().unwrap().is_none());
    }

    // -- redaction ---------------------------------------------------------

    #[test]
    fn redact_in_place_zeroes_ipv4_endpoints() {
        let mut packet = make_ipv4_tcp([192, 168, 1, 100], [8, 8, 8, 8], b"payload");
        redact_in_place(&mut packet);
        assert_eq!(&packet[12..16], &[0u8; 4], "src not zeroed");
        assert_eq!(&packet[16..20], &[0u8; 4], "dst not zeroed");
        assert!(ipv4_header_checksum_valid(&packet), "IPv4 checksum not recomputed: {:02x?}", &packet[..20]);
        // TCP checksum zeroed.
        assert_eq!(packet[20 + 16], 0);
        assert_eq!(packet[20 + 17], 0);
    }

    #[test]
    fn redact_in_place_zeroes_ipv6_endpoints() {
        let src = [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01];
        let dst = [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x02];
        let mut packet = make_ipv6_udp(src, dst, b"hello");
        redact_in_place(&mut packet);
        assert_eq!(&packet[8..24], &[0u8; 16], "src not zeroed");
        assert_eq!(&packet[24..40], &[0u8; 16], "dst not zeroed");
        // UDP checksum zeroed.
        assert_eq!(packet[40 + 6], 0);
        assert_eq!(packet[40 + 7], 0);
    }

    #[test]
    fn redact_in_place_handles_short_packet() {
        let mut tiny = vec![0x45, 0, 0, 0, 0];
        // Should not panic - just returns without action because the
        // buffer is shorter than the IPv4 minimum (20 bytes).
        redact_in_place(&mut tiny);
        assert_eq!(tiny, vec![0x45, 0, 0, 0, 0]);

        // Empty buffer: also no panic.
        let mut empty: Vec<u8> = Vec::new();
        redact_in_place(&mut empty);
        assert!(empty.is_empty());
    }

    #[test]
    fn redact_in_place_scrubs_ipv4_tls_sni() {
        let sni = "secret-sni.example";
        let hello = make_client_hello_with_sni(sni);
        let mut packet = make_ipv4_tcp([192, 168, 1, 100], [8, 8, 8, 8], &hello);
        // Sanity: the SNI is present before redaction.
        assert!(packet.windows(sni.len()).any(|w| w == sni.as_bytes()), "SNI should be present pre-redaction");
        redact_in_place(&mut packet);
        assert!(!packet.windows(sni.len()).any(|w| w == sni.as_bytes()), "SNI survived redaction: {packet:02x?}");
        // Endpoint redaction still works and checksum stays valid.
        assert_eq!(&packet[12..16], &[0u8; 4]);
        assert_eq!(&packet[16..20], &[0u8; 4]);
        assert!(ipv4_header_checksum_valid(&packet));
    }

    #[test]
    fn redact_in_place_scrubs_ipv6_tls_sni() {
        let sni = "secret-sni.example";
        let hello = make_client_hello_with_sni(sni);
        let src = [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x01];
        let dst = [0x20, 0x01, 0x0d, 0xb8, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0x02];
        // make_ipv6_udp builds a UDP packet; rebuild as TCP for the TLS case.
        let mut packet = make_ipv6_udp(src, dst, &hello);
        packet[6] = 6; // next header = TCP
        // UDP header (8 bytes) -> TCP header must be >= 20 bytes with a
        // valid data offset for the SNI scrubber to find the payload.
        let payload_start = 40;
        // Rebuild a TCP segment: 20-byte header + the TLS record.
        let mut tcp_seg = vec![0u8; 20];
        tcp_seg[12] = 0x50; // data offset = 5 * 4 = 20 bytes
        tcp_seg.extend_from_slice(&hello);
        packet.truncate(payload_start);
        packet.extend_from_slice(&tcp_seg);
        // Fix IPv6 payload length.
        let plen = (packet.len() - 40) as u16;
        packet[4..6].copy_from_slice(&plen.to_be_bytes());
        assert!(packet.windows(sni.len()).any(|w| w == sni.as_bytes()), "SNI should be present pre-redaction");
        redact_in_place(&mut packet);
        assert!(!packet.windows(sni.len()).any(|w| w == sni.as_bytes()), "SNI survived IPv6 redaction");
        assert_eq!(&packet[8..24], &[0u8; 16]);
        assert_eq!(&packet[24..40], &[0u8; 16]);
    }

    #[test]
    fn redact_in_place_leaves_non_tls_tcp_payload_unchanged() {
        // A TCP payload that is not a TLS handshake must be left intact and
        // must not panic.
        let payload = b"GET / HTTP/1.1\r\nHost: secret-sni.example\r\n\r\n";
        let mut packet = make_ipv4_tcp([10, 0, 0, 1], [10, 0, 0, 2], payload);
        let payload_before = packet[40..].to_vec();
        redact_in_place(&mut packet);
        // The HTTP body (including its Host header) is not a ClientHello, so
        // the SNI-scrub leaves it untouched.
        assert_eq!(&packet[40..], &payload_before[..], "non-TLS payload mutated");
    }

    #[test]
    fn redact_tcp_sni_does_not_panic_on_truncated_client_hello() {
        let sni = "secret-sni.example";
        let full = make_client_hello_with_sni(sni);
        // Feed progressively truncated ClientHellos; none may panic.
        for cut in 0..full.len() {
            let mut packet = make_ipv4_tcp([10, 0, 0, 1], [10, 0, 0, 2], &full[..cut]);
            redact_in_place(&mut packet);
        }
    }

    #[test]
    fn rewrite_endpoints_stream_roundtrips_three_packets() {
        let p1 = make_ipv4_tcp([10, 0, 0, 1], [10, 0, 0, 2], b"alpha");
        let p2 = make_ipv4_tcp([10, 0, 0, 3], [10, 0, 0, 4], b"beta");
        let p3 = make_ipv4_tcp([10, 0, 0, 5], [10, 0, 0, 6], b"gamma");
        let mut src_buf = Vec::new();
        {
            let mut writer = PcapWriter::new(&mut src_buf, SNAPLEN_DEFAULT).unwrap();
            writer.write_packet(100, &p1).unwrap();
            writer.write_packet(200, &p2).unwrap();
            writer.write_packet(300, &p3).unwrap();
        }
        let mut dst_buf = Vec::new();
        let written = rewrite_endpoints(Cursor::new(&src_buf), &mut dst_buf).unwrap();
        assert!(written > 24);
        let mut reader = PcapReader::new(Cursor::new(&dst_buf)).unwrap();
        for expected_ts in [100u64, 200, 300] {
            let record = reader.next_record().unwrap().expect("record present");
            assert_eq!(record.ts_micros, expected_ts);
            assert_eq!(&record.bytes[12..16], &[0u8; 4]);
            assert_eq!(&record.bytes[16..20], &[0u8; 4]);
            assert!(ipv4_header_checksum_valid(&record.bytes));
        }
        assert!(reader.next_record().unwrap().is_none());
    }

    // -- proptest ----------------------------------------------------------

    proptest! {
        #[test]
        fn prop_writer_reader_roundtrip_any_ipv4_packet(
            payload in proptest::collection::vec(any::<u8>(), 0..1456),
            ts_micros in 0u64..(u64::from(u32::MAX) * 1_000_000),
            src in any::<[u8; 4]>(),
            dst in any::<[u8; 4]>(),
        ) {
            let packet = make_ipv4_tcp(src, dst, &payload);
            let mut buf = Vec::new();
            {
                let mut writer = PcapWriter::new(&mut buf, SNAPLEN_DEFAULT).unwrap();
                writer.write_packet(ts_micros, &packet).unwrap();
            }
            let mut reader = PcapReader::new(Cursor::new(&buf)).unwrap();
            let record = reader.next_record().unwrap().expect("record present");
            prop_assert_eq!(record.ts_micros, ts_micros);
            prop_assert_eq!(record.orig_len, packet.len() as u32);
            prop_assert_eq!(record.bytes, packet);
            prop_assert!(reader.next_record().unwrap().is_none());
        }
    }
}
