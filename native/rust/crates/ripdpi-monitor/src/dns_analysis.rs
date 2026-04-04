//! DNS response analysis for tampering detection.
//!
//! Inspects raw DNS response bytes to extract protocol-level anomaly signals
//! that indicate forged responses (middlebox injection, ISP DNS hijacking, etc.).
//! Uses a two-layer approach: raw byte header parsing (always works) plus
//! hickory-proto `Message` parsing (best-effort, graceful fallback).

use hickory_proto::op::Message;
use hickory_proto::rr::{RData, RecordType};

/// Tampering signals extracted from a raw DNS response.
#[derive(Debug, Clone, Default)]
pub(crate) struct DnsResponseAnalysis {
    // Header flags (from raw bytes).
    pub aa_flag: bool,
    pub tc_flag: bool,
    pub ra_flag: bool,
    pub rcode: u8,

    // Section counts.
    pub answer_count: u16,
    pub authority_count: u16,
    pub additional_count: u16,

    // TTL analysis (from answer section via hickory).
    pub min_ttl: Option<u32>,
    pub max_ttl: Option<u32>,
    pub ttl_uniform: bool,

    // EDNS0 / OPT record presence.
    pub has_edns0: bool,

    // CNAME targets found in answer section.
    pub cname_targets: Vec<String>,

    // Raw response size in bytes.
    pub response_size: usize,

    // Composite tampering score (0-100).
    pub tampering_score: u32,

    // Names of triggered anomaly signals.
    pub signals: Vec<&'static str>,
}

/// Analyze a raw DNS response packet for tampering indicators.
///
/// Returns useful results even on malformed packets: the raw byte layer
/// always extracts header flags and section counts from the first 12 bytes.
pub(crate) fn analyze_dns_response(packet: &[u8]) -> DnsResponseAnalysis {
    let mut analysis = DnsResponseAnalysis { response_size: packet.len(), ..Default::default() };

    // Layer 1: raw byte header parsing (works on any 12+ byte packet).
    if packet.len() >= 12 {
        let flags = u16::from_be_bytes([packet[2], packet[3]]);
        analysis.aa_flag = flags & 0x0400 != 0;
        analysis.tc_flag = flags & 0x0200 != 0;
        analysis.ra_flag = flags & 0x0080 != 0;
        analysis.rcode = (flags & 0x000F) as u8;
        analysis.answer_count = u16::from_be_bytes([packet[6], packet[7]]);
        analysis.authority_count = u16::from_be_bytes([packet[8], packet[9]]);
        analysis.additional_count = u16::from_be_bytes([packet[10], packet[11]]);
    }

    // Layer 2: hickory-proto structured parsing (best-effort).
    if let Ok(message) = Message::from_vec(packet) {
        let mut ttls: Vec<u32> = Vec::new();

        for record in message.answers() {
            ttls.push(record.ttl());
            if let RData::CNAME(ref name) = record.data() {
                analysis.cname_targets.push(name.to_string());
            }
        }

        if !ttls.is_empty() {
            let min = *ttls.iter().min().unwrap();
            let max = *ttls.iter().max().unwrap();
            analysis.min_ttl = Some(min);
            analysis.max_ttl = Some(max);
            analysis.ttl_uniform = min == max;
        }

        // OPT record presence (EDNS0) -- hickory exposes via extensions().
        analysis.has_edns0 = message.extensions().is_some();
    }

    // Compute tampering signals and score.
    compute_tampering_score(&mut analysis);

    analysis
}

/// Signal weights for tampering score computation.
const WEIGHT_AA_ON_RECURSIVE: u32 = 15;
const WEIGHT_NO_AUTHORITY: u32 = 10;
const WEIGHT_NO_ADDITIONAL: u32 = 10;
const WEIGHT_SUSPICIOUS_TTL: u32 = 15;
const WEIGHT_NO_EDNS0: u32 = 10;
const WEIGHT_SMALL_RESPONSE: u32 = 10;
const WEIGHT_SINGLE_ANSWER: u32 = 5;

fn compute_tampering_score(analysis: &mut DnsResponseAnalysis) {
    let mut score: u32 = 0;

    // AA flag set when querying a recursive resolver is suspicious:
    // forged responses from in-path DPI equipment typically set AA=1.
    if analysis.aa_flag {
        score += WEIGHT_AA_ON_RECURSIVE;
        analysis.signals.push("aa_on_recursive");
    }

    // Legitimate recursive resolver responses usually include NS records
    // in the authority section.
    if analysis.authority_count == 0 {
        score += WEIGHT_NO_AUTHORITY;
        analysis.signals.push("no_authority");
    }

    // Missing additional section AND no EDNS0 OPT record.
    if analysis.additional_count == 0 && !analysis.has_edns0 {
        score += WEIGHT_NO_ADDITIONAL;
        analysis.signals.push("no_additional");
    }

    // TTL anomalies: zero TTL or exact round numbers (86400, 3600) with
    // uniform TTL across all answers suggest a static forged response.
    if let Some(min) = analysis.min_ttl {
        let is_round = |ttl: u32| matches!(ttl, 0 | 300 | 600 | 3600 | 7200 | 86400);
        if min == 0 || (analysis.ttl_uniform && is_round(min)) {
            score += WEIGHT_SUSPICIOUS_TTL;
            analysis.signals.push("suspicious_ttl");
        }
    }

    // Modern recursive resolvers include EDNS0 OPT records. Absence
    // suggests a minimal forged response.
    if !analysis.has_edns0 && analysis.response_size >= 12 {
        score += WEIGHT_NO_EDNS0;
        analysis.signals.push("no_edns0");
    }

    // Forged responses tend to be very small (just header + 1 answer).
    if analysis.response_size > 0 && analysis.response_size < 64 {
        score += WEIGHT_SMALL_RESPONSE;
        analysis.signals.push("small_response");
    }

    // Single A/AAAA answer for domains that typically return many (CDNs).
    if analysis.answer_count == 1 {
        score += WEIGHT_SINGLE_ANSWER;
        analysis.signals.push("single_answer");
    }

    analysis.tampering_score = score.min(100);
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Build a minimal valid DNS response with configurable sections.
    fn build_dns_response(config: &ResponseConfig) -> Vec<u8> {
        let mut pkt = Vec::with_capacity(512);

        // Header (12 bytes).
        pkt.extend(config.id.to_be_bytes());
        let mut flags: u16 = 0x8000; // QR=1 (response)
        if config.aa {
            flags |= 0x0400;
        }
        if config.ra {
            flags |= 0x0080;
        }
        flags |= (config.rcode as u16) & 0x000F;
        pkt.extend(flags.to_be_bytes());
        pkt.extend(1u16.to_be_bytes()); // QDCOUNT = 1
        pkt.extend(config.answer_count.to_be_bytes());
        pkt.extend(config.authority_count.to_be_bytes());
        pkt.extend(config.additional_count.to_be_bytes());

        // Question section: example.com, type A, class IN.
        pkt.push(7);
        pkt.extend(b"example");
        pkt.push(3);
        pkt.extend(b"com");
        pkt.push(0);
        pkt.extend(1u16.to_be_bytes()); // QTYPE A
        pkt.extend(1u16.to_be_bytes()); // QCLASS IN

        // Answer records.
        for answer in &config.answers {
            // Name pointer to offset 12 (question name).
            pkt.extend(0xC00Cu16.to_be_bytes());
            pkt.extend(answer.rtype.to_be_bytes());
            pkt.extend(1u16.to_be_bytes()); // CLASS IN
            pkt.extend(answer.ttl.to_be_bytes());
            pkt.extend((answer.rdata.len() as u16).to_be_bytes());
            pkt.extend(&answer.rdata);
        }

        // Authority records (NS pointing to ns1.example.com).
        for _ in 0..config.authority_count {
            pkt.extend(0xC00Cu16.to_be_bytes()); // name pointer
            pkt.extend(2u16.to_be_bytes()); // TYPE NS
            pkt.extend(1u16.to_be_bytes()); // CLASS IN
            pkt.extend(3600u32.to_be_bytes()); // TTL
            let ns_name = b"\x03ns1\x07example\x03com\x00";
            pkt.extend((ns_name.len() as u16).to_be_bytes());
            pkt.extend(ns_name.as_slice());
        }

        // OPT record in additional section (if requested).
        if config.include_edns0 {
            pkt.push(0); // root name
            pkt.extend(41u16.to_be_bytes()); // TYPE OPT
            pkt.extend(4096u16.to_be_bytes()); // UDP payload size
            pkt.extend(0u32.to_be_bytes()); // extended rcode + version + flags
            pkt.extend(0u16.to_be_bytes()); // RDLENGTH = 0
        }

        pkt
    }

    struct ResponseConfig {
        id: u16,
        aa: bool,
        ra: bool,
        rcode: u8,
        answer_count: u16,
        authority_count: u16,
        additional_count: u16,
        answers: Vec<AnswerRecord>,
        include_edns0: bool,
    }

    struct AnswerRecord {
        rtype: u16,
        ttl: u32,
        rdata: Vec<u8>,
    }

    impl Default for ResponseConfig {
        fn default() -> Self {
            Self {
                id: 0xABCD,
                aa: false,
                ra: true,
                rcode: 0,
                answer_count: 0,
                authority_count: 0,
                additional_count: 0,
                answers: Vec::new(),
                include_edns0: false,
            }
        }
    }

    fn a_record(ip: [u8; 4], ttl: u32) -> AnswerRecord {
        AnswerRecord { rtype: 1, ttl, rdata: ip.to_vec() }
    }

    fn cname_record(target: &str, ttl: u32) -> AnswerRecord {
        let mut rdata = Vec::new();
        for label in target.split('.') {
            rdata.push(label.len() as u8);
            rdata.extend(label.as_bytes());
        }
        rdata.push(0);
        AnswerRecord { rtype: 5, ttl, rdata }
    }

    #[test]
    fn legitimate_response_scores_low() {
        let pkt = build_dns_response(&ResponseConfig {
            ra: true,
            answer_count: 4,
            authority_count: 2,
            additional_count: 1, // OPT record
            answers: vec![
                a_record([93, 184, 216, 34], 287),
                a_record([93, 184, 216, 35], 287),
                a_record([93, 184, 216, 36], 310),
                a_record([93, 184, 216, 37], 295),
            ],
            include_edns0: true,
            ..Default::default()
        });
        let analysis = analyze_dns_response(&pkt);
        assert!(
            analysis.tampering_score < 20,
            "legitimate response should score < 20, got {}",
            analysis.tampering_score
        );
        assert!(!analysis.aa_flag);
        assert!(analysis.has_edns0);
        assert_eq!(analysis.authority_count, 2);
    }

    #[test]
    fn forged_response_scores_high() {
        let pkt = build_dns_response(&ResponseConfig {
            aa: true, // Forged: AA on recursive
            ra: true,
            answer_count: 1,
            authority_count: 0,                         // Forged: no authority
            additional_count: 0,                        // Forged: no additional
            answers: vec![a_record([127, 0, 0, 1], 0)], // Forged: TTL=0
            include_edns0: false,
            ..Default::default()
        });
        let analysis = analyze_dns_response(&pkt);
        assert!(
            analysis.tampering_score >= 60,
            "forged response should score >= 60, got {} (signals: {:?})",
            analysis.tampering_score,
            analysis.signals
        );
        assert!(analysis.aa_flag);
        assert!(!analysis.has_edns0);
        assert_eq!(analysis.authority_count, 0);
        assert!(analysis.signals.contains(&"aa_on_recursive"));
        assert!(analysis.signals.contains(&"no_authority"));
        assert!(analysis.signals.contains(&"suspicious_ttl"));
    }

    #[test]
    fn cname_redirect_detected() {
        let pkt = build_dns_response(&ResponseConfig {
            answer_count: 2,
            authority_count: 1,
            additional_count: 1,
            answers: vec![cname_record("blocked.isp.example", 300), a_record([10, 0, 0, 1], 300)],
            include_edns0: true,
            ..Default::default()
        });
        let analysis = analyze_dns_response(&pkt);
        assert_eq!(analysis.cname_targets.len(), 1);
        assert!(analysis.cname_targets[0].contains("blocked.isp.example"));
    }

    #[test]
    fn malformed_packet_uses_raw_layer() {
        // 12-byte header with garbage after it.
        let mut pkt = vec![0u8; 12];
        pkt[2] = 0x84; // QR=1, AA=1
        pkt[3] = 0x80; // RA=1
        pkt[7] = 1; // ANCOUNT=1
        pkt.extend(b"\xff\xff\xff"); // garbage
        let analysis = analyze_dns_response(&pkt);
        // Raw layer should extract flags even though hickory will fail.
        assert!(analysis.aa_flag);
        assert!(analysis.ra_flag);
        assert_eq!(analysis.answer_count, 1);
        assert_eq!(analysis.response_size, 15);
    }

    #[test]
    fn nxdomain_with_aa_flag() {
        let pkt = build_dns_response(&ResponseConfig {
            aa: true,
            rcode: 3, // NXDOMAIN
            answer_count: 0,
            authority_count: 0,
            additional_count: 0,
            answers: vec![],
            include_edns0: false,
            ..Default::default()
        });
        let analysis = analyze_dns_response(&pkt);
        assert_eq!(analysis.rcode, 3);
        assert!(analysis.aa_flag);
        assert!(analysis.signals.contains(&"aa_on_recursive"));
    }

    #[test]
    fn too_short_packet_returns_defaults() {
        let pkt = vec![0u8; 5];
        let analysis = analyze_dns_response(&pkt);
        assert_eq!(analysis.response_size, 5);
        assert!(analysis.signals.contains(&"small_response"));
        // Header fields should remain default (false/0).
        assert!(!analysis.aa_flag);
        assert_eq!(analysis.answer_count, 0);
    }

    #[test]
    fn uniform_round_ttl_is_suspicious() {
        let pkt = build_dns_response(&ResponseConfig {
            answer_count: 2,
            authority_count: 1,
            additional_count: 1,
            answers: vec![a_record([1, 2, 3, 4], 86400), a_record([5, 6, 7, 8], 86400)],
            include_edns0: true,
            ..Default::default()
        });
        let analysis = analyze_dns_response(&pkt);
        assert!(analysis.ttl_uniform);
        assert!(analysis.signals.contains(&"suspicious_ttl"));
    }

    #[test]
    fn varied_non_round_ttls_are_not_suspicious() {
        let pkt = build_dns_response(&ResponseConfig {
            answer_count: 2,
            authority_count: 1,
            additional_count: 1,
            answers: vec![a_record([1, 2, 3, 4], 287), a_record([5, 6, 7, 8], 310)],
            include_edns0: true,
            ..Default::default()
        });
        let analysis = analyze_dns_response(&pkt);
        assert!(!analysis.ttl_uniform);
        assert!(!analysis.signals.contains(&"suspicious_ttl"));
    }
}
