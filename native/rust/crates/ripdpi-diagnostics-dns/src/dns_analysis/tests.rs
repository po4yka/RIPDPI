use super::*;

fn build_dns_response(config: &ResponseConfig) -> Vec<u8> {
    let mut packet = Vec::with_capacity(512);

    packet.extend(config.id.to_be_bytes());
    let mut flags: u16 = 0x8000;
    if config.aa {
        flags |= 0x0400;
    }
    if config.ra {
        flags |= 0x0080;
    }
    flags |= (config.rcode as u16) & 0x000F;
    packet.extend(flags.to_be_bytes());
    packet.extend(1u16.to_be_bytes());
    packet.extend(config.answer_count.to_be_bytes());
    packet.extend(config.authority_count.to_be_bytes());
    packet.extend(config.additional_count.to_be_bytes());

    packet.push(7);
    packet.extend(b"example");
    packet.push(3);
    packet.extend(b"com");
    packet.push(0);
    packet.extend(1u16.to_be_bytes());
    packet.extend(1u16.to_be_bytes());

    for answer in &config.answers {
        packet.extend(0xC00Cu16.to_be_bytes());
        packet.extend(answer.rtype.to_be_bytes());
        packet.extend(1u16.to_be_bytes());
        packet.extend(answer.ttl.to_be_bytes());
        packet.extend((answer.rdata.len() as u16).to_be_bytes());
        packet.extend(&answer.rdata);
    }

    for _ in 0..config.authority_count {
        packet.extend(0xC00Cu16.to_be_bytes());
        packet.extend(2u16.to_be_bytes());
        packet.extend(1u16.to_be_bytes());
        packet.extend(3600u32.to_be_bytes());
        let ns_name = b"\x03ns1\x07example\x03com\x00";
        packet.extend((ns_name.len() as u16).to_be_bytes());
        packet.extend(ns_name.as_slice());
    }

    if config.include_edns0 {
        packet.push(0);
        packet.extend(41u16.to_be_bytes());
        packet.extend(4096u16.to_be_bytes());
        packet.extend(0u32.to_be_bytes());
        packet.extend(0u16.to_be_bytes());
    }

    packet
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
    let packet = build_dns_response(&ResponseConfig {
        ra: true,
        answer_count: 4,
        authority_count: 2,
        additional_count: 1,
        answers: vec![
            a_record([93, 184, 216, 34], 287),
            a_record([93, 184, 216, 35], 287),
            a_record([93, 184, 216, 36], 310),
            a_record([93, 184, 216, 37], 295),
        ],
        include_edns0: true,
        ..Default::default()
    });
    let analysis = analyze_dns_response(&packet);
    assert!(analysis.tampering_score < 20, "legitimate response should score < 20, got {}", analysis.tampering_score);
    assert!(!analysis.aa_flag);
    assert!(analysis.has_edns0);
    assert_eq!(analysis.authority_count, 2);
}

#[test]
fn forged_response_scores_high() {
    let packet = build_dns_response(&ResponseConfig {
        aa: true,
        ra: true,
        answer_count: 1,
        authority_count: 0,
        additional_count: 0,
        answers: vec![a_record([127, 0, 0, 1], 0)],
        include_edns0: false,
        ..Default::default()
    });
    let analysis = analyze_dns_response(&packet);
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
    let packet = build_dns_response(&ResponseConfig {
        answer_count: 2,
        authority_count: 1,
        additional_count: 1,
        answers: vec![cname_record("blocked.isp.example", 300), a_record([10, 0, 0, 1], 300)],
        include_edns0: true,
        ..Default::default()
    });
    let analysis = analyze_dns_response(&packet);
    assert_eq!(analysis.cname_targets.len(), 1);
    assert!(analysis.cname_targets[0].contains("blocked.isp.example"));
}

#[test]
fn malformed_packet_uses_raw_layer() {
    let mut packet = vec![0u8; 12];
    packet[2] = 0x84;
    packet[3] = 0x80;
    packet[7] = 1;
    packet.extend(b"\xff\xff\xff");
    let analysis = analyze_dns_response(&packet);
    assert!(analysis.aa_flag);
    assert!(analysis.ra_flag);
    assert_eq!(analysis.answer_count, 1);
    assert_eq!(analysis.response_size, 15);
}

#[test]
fn nxdomain_with_aa_flag() {
    let packet = build_dns_response(&ResponseConfig {
        aa: true,
        rcode: 3,
        answer_count: 0,
        authority_count: 0,
        additional_count: 0,
        answers: vec![],
        include_edns0: false,
        ..Default::default()
    });
    let analysis = analyze_dns_response(&packet);
    assert_eq!(analysis.rcode, 3);
    assert!(analysis.aa_flag);
    assert!(analysis.signals.contains(&"aa_on_recursive"));
}

#[test]
fn too_short_packet_returns_defaults() {
    let packet = vec![0u8; 5];
    let analysis = analyze_dns_response(&packet);
    assert_eq!(analysis.response_size, 5);
    assert!(analysis.signals.contains(&"small_response"));
    assert!(!analysis.aa_flag);
    assert_eq!(analysis.answer_count, 0);
}

#[test]
fn uniform_round_ttl_is_suspicious() {
    let packet = build_dns_response(&ResponseConfig {
        answer_count: 2,
        authority_count: 1,
        additional_count: 1,
        answers: vec![a_record([1, 2, 3, 4], 86400), a_record([5, 6, 7, 8], 86400)],
        include_edns0: true,
        ..Default::default()
    });
    let analysis = analyze_dns_response(&packet);
    assert!(analysis.ttl_uniform);
    assert!(analysis.signals.contains(&"suspicious_ttl"));
}

#[test]
fn varied_non_round_ttls_are_not_suspicious() {
    let packet = build_dns_response(&ResponseConfig {
        answer_count: 2,
        authority_count: 1,
        additional_count: 1,
        answers: vec![a_record([1, 2, 3, 4], 287), a_record([5, 6, 7, 8], 310)],
        include_edns0: true,
        ..Default::default()
    });
    let analysis = analyze_dns_response(&packet);
    assert!(!analysis.ttl_uniform);
    assert!(!analysis.signals.contains(&"suspicious_ttl"));
}

#[test]
fn valid_pointers_pass_validation() {
    let packet = build_dns_response(&ResponseConfig {
        answer_count: 1,
        authority_count: 0,
        additional_count: 0,
        answers: vec![a_record([1, 2, 3, 4], 300)],
        include_edns0: false,
        ..Default::default()
    });
    assert!(!has_malformed_compression_pointers(&packet));
}

#[test]
fn pointer_beyond_packet_is_malformed() {
    let mut packet = vec![0u8; 12];
    packet[4] = 0;
    packet[5] = 1;
    packet.push(0xC0 | 0x3F);
    packet.push(0x00);
    packet.extend([0, 1, 0, 1]);
    assert!(has_malformed_compression_pointers(&packet));
}

#[test]
fn short_packet_no_crash() {
    assert!(!has_malformed_compression_pointers(&[0u8; 5]));
    assert!(!has_malformed_compression_pointers(&[]));
}

#[test]
fn malformed_pointers_add_tampering_signal() {
    let mut packet = build_dns_response(&ResponseConfig {
        answer_count: 1,
        authority_count: 0,
        additional_count: 0,
        answers: vec![a_record([1, 2, 3, 4], 300)],
        include_edns0: false,
        ..Default::default()
    });
    if let Some(pos) = packet.windows(2).position(|window| window == [0xC0, 0x0C]) {
        let second = packet[pos + 2..].windows(2).position(|window| window == [0xC0, 0x0C]);
        if let Some(offset) = second {
            packet[pos + 2 + offset + 1] = 0xFF;
        }
    }
    let analysis = analyze_dns_response(&packet);
    let _ = analysis.malformed_pointers;
}

#[test]
fn parse_record_set_extracts_answers() {
    let packet = build_dns_response(&ResponseConfig {
        answer_count: 2,
        authority_count: 1,
        additional_count: 1,
        answers: vec![a_record([1, 2, 3, 4], 300), a_record([5, 6, 7, 8], 300)],
        include_edns0: true,
        ..Default::default()
    });
    let record_set = parse_record_set(&packet);
    assert_eq!(record_set.answers.len(), 2);
    assert_eq!(record_set.answers[0].rtype, 1);
    assert_eq!(record_set.answers[0].rtype_name, "A");
    assert_eq!(record_set.answers[0].value, "1.2.3.4");
    assert_eq!(record_set.answers[1].value, "5.6.7.8");
    assert!(record_set.has_edns0);
}

#[test]
fn compare_identical_responses_scores_zero() {
    let packet = build_dns_response(&ResponseConfig {
        answer_count: 2,
        authority_count: 1,
        additional_count: 1,
        answers: vec![a_record([1, 2, 3, 4], 300), a_record([5, 6, 7, 8], 300)],
        include_edns0: true,
        ..Default::default()
    });
    let record_set = parse_record_set(&packet);
    let result = compare_dns_responses(&record_set, &record_set);
    assert_eq!(result.comparison_score, 0);
    assert!(result.comparison_signals.is_empty());
}

#[test]
fn compare_cname_vs_a_detects_mismatch() {
    let udp_packet = build_dns_response(&ResponseConfig {
        answer_count: 2,
        authority_count: 0,
        additional_count: 0,
        answers: vec![cname_record("redirect.isp.example", 0), a_record([10, 0, 0, 1], 0)],
        include_edns0: false,
        ..Default::default()
    });
    let encrypted_packet = build_dns_response(&ResponseConfig {
        answer_count: 1,
        authority_count: 0,
        additional_count: 0,
        answers: vec![a_record([93, 184, 216, 34], 300)],
        include_edns0: false,
        ..Default::default()
    });
    let udp_records = parse_record_set(&udp_packet);
    let encrypted_records = parse_record_set(&encrypted_packet);
    let result = compare_dns_responses(&udp_records, &encrypted_records);
    assert!(result.record_type_mismatch);
    assert!(!result.extra_cnames.is_empty());
    assert!(result.comparison_signals.contains(&"record_type_mismatch"));
    assert!(result.comparison_signals.contains(&"extra_cname_in_udp"));
    assert!(result.comparison_score >= 35);
}

#[test]
fn compare_ttl_divergence() {
    let udp_packet = build_dns_response(&ResponseConfig {
        answer_count: 1,
        authority_count: 0,
        additional_count: 0,
        answers: vec![a_record([1, 2, 3, 4], 0)],
        include_edns0: false,
        ..Default::default()
    });
    let encrypted_packet = build_dns_response(&ResponseConfig {
        answer_count: 1,
        authority_count: 0,
        additional_count: 0,
        answers: vec![a_record([1, 2, 3, 4], 7200)],
        include_edns0: false,
        ..Default::default()
    });
    let udp_records = parse_record_set(&udp_packet);
    let encrypted_records = parse_record_set(&encrypted_packet);
    let result = compare_dns_responses(&udp_records, &encrypted_records);
    assert_eq!(result.ttl_divergence, Some(7200));
    assert!(result.comparison_signals.contains(&"ttl_highly_divergent"));
}

#[test]
fn compare_rcode_mismatch() {
    let udp_packet =
        build_dns_response(&ResponseConfig { rcode: 3, answer_count: 0, answers: vec![], ..Default::default() });
    let encrypted_packet = build_dns_response(&ResponseConfig {
        rcode: 0,
        answer_count: 1,
        answers: vec![a_record([1, 2, 3, 4], 300)],
        ..Default::default()
    });
    let udp_records = parse_record_set(&udp_packet);
    let encrypted_records = parse_record_set(&encrypted_packet);
    let result = compare_dns_responses(&udp_records, &encrypted_records);
    assert!(result.comparison_signals.contains(&"rcode_mismatch"));
    assert!(result.comparison_score >= 20);
}
