use std::collections::BTreeSet;

use hickory_proto::op::Message;
use hickory_proto::rr::RData;

use super::wire_header::parse_header;

/// A single DNS resource record for cross-resolver comparison.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DnsRecord {
    pub rtype: u16,
    pub rtype_name: &'static str,
    pub value: String,
    pub ttl: u32,
}

/// Structured representation of DNS response sections.
#[derive(Debug, Clone, Default)]
pub struct DnsRecordSet {
    pub answers: Vec<DnsRecord>,
    pub authority: Vec<DnsRecord>,
    pub rcode: u8,
    pub aa_flag: bool,
    pub has_edns0: bool,
}

/// Result of comparing UDP vs encrypted DNS responses.
#[derive(Debug, Clone, Default)]
pub struct DnsComparisonResult {
    pub record_type_mismatch: bool,
    pub answer_count_divergence: i32,
    pub ttl_divergence: Option<u32>,
    pub authority_mismatch: bool,
    pub extra_cnames: Vec<String>,
    pub comparison_signals: Vec<&'static str>,
    pub comparison_score: u32,
}

/// Parse a raw DNS response into a structured [`DnsRecordSet`].
pub fn parse_record_set(packet: &[u8]) -> DnsRecordSet {
    let mut record_set = DnsRecordSet::default();

    if let Some(header) = parse_header(packet) {
        record_set.aa_flag = header.aa_flag;
        record_set.rcode = header.rcode;
    }

    if let Ok(message) = Message::from_vec(packet) {
        record_set.has_edns0 = message.edns.is_some();

        for record in &message.answers {
            let rtype = record.record_type().into();
            let value = format_rdata(&record.data);
            record_set.answers.push(DnsRecord { rtype, rtype_name: rtype_name(rtype), value, ttl: record.ttl });
        }

        for record in &message.authorities {
            let rtype = record.record_type().into();
            let value = format_rdata(&record.data);
            record_set.authority.push(DnsRecord { rtype, rtype_name: rtype_name(rtype), value, ttl: record.ttl });
        }
    }

    record_set
}

/// Compare two DNS record sets (typically UDP vs encrypted resolver).
pub fn compare_dns_responses(udp: &DnsRecordSet, encrypted: &DnsRecordSet) -> DnsComparisonResult {
    let mut result = DnsComparisonResult::default();

    let udp_types: BTreeSet<u16> = udp.answers.iter().map(|record| record.rtype).collect();
    let encrypted_types: BTreeSet<u16> = encrypted.answers.iter().map(|record| record.rtype).collect();
    if !udp_types.is_empty() && !encrypted_types.is_empty() && udp_types != encrypted_types {
        result.record_type_mismatch = true;
        result.comparison_signals.push("record_type_mismatch");
        result.comparison_score += 20;
    }

    result.answer_count_divergence = udp.answers.len() as i32 - encrypted.answers.len() as i32;
    if result.answer_count_divergence.unsigned_abs() >= 3 {
        result.comparison_signals.push("answer_count_divergent");
        result.comparison_score += 10;
    }

    let udp_ttls: Vec<u32> =
        udp.answers.iter().filter(|record| is_address_record(record.rtype)).map(|record| record.ttl).collect();
    let encrypted_ttls: Vec<u32> =
        encrypted.answers.iter().filter(|record| is_address_record(record.rtype)).map(|record| record.ttl).collect();
    if let (Some(&udp_ttl), Some(&encrypted_ttl)) = (udp_ttls.first(), encrypted_ttls.first()) {
        let diff = udp_ttl.abs_diff(encrypted_ttl);
        if diff > 0 {
            result.ttl_divergence = Some(diff);
        }
        if diff > 3600 {
            result.comparison_signals.push("ttl_highly_divergent");
            result.comparison_score += 15;
        }
    }

    if !encrypted.authority.is_empty() && udp.authority.is_empty() {
        result.authority_mismatch = true;
        result.comparison_signals.push("authority_missing_in_udp");
        result.comparison_score += 10;
    }

    let encrypted_cnames: BTreeSet<&str> =
        encrypted.answers.iter().filter(|record| record.rtype == 5).map(|record| record.value.as_str()).collect();
    for record in udp.answers.iter().filter(|record| record.rtype == 5) {
        if !encrypted_cnames.contains(record.value.as_str()) {
            result.extra_cnames.push(record.value.clone());
        }
    }
    if !result.extra_cnames.is_empty() {
        result.comparison_signals.push("extra_cname_in_udp");
        result.comparison_score += 15;
    }

    if udp.rcode != encrypted.rcode {
        result.comparison_signals.push("rcode_mismatch");
        result.comparison_score += 20;
    }

    result.comparison_score = result.comparison_score.min(100);
    result
}

fn is_address_record(rtype: u16) -> bool {
    rtype == 1 || rtype == 28
}

fn rtype_name(rtype: u16) -> &'static str {
    match rtype {
        1 => "A",
        2 => "NS",
        5 => "CNAME",
        6 => "SOA",
        15 => "MX",
        16 => "TXT",
        28 => "AAAA",
        33 => "SRV",
        41 => "OPT",
        _ => "OTHER",
    }
}

fn format_rdata(rdata: &RData) -> String {
    match rdata {
        RData::A(addr) => addr.to_string(),
        RData::AAAA(addr) => addr.to_string(),
        RData::CNAME(name) => name.to_string(),
        RData::NS(name) => name.to_string(),
        _ => format!("{rdata:?}"),
    }
}
