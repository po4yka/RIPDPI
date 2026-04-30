use crate::dns_analysis::{analyze_dns_response, compare_dns_responses, parse_record_set};
use crate::types::ProbeResult;

use super::super::support::{push_detail, push_joined_str_detail, push_joined_string_detail};

#[inline(never)]
pub(super) fn append_udp_response_analysis(result: &mut ProbeResult, raw: &[u8]) {
    let analysis = analyze_dns_response(raw);
    push_detail(&mut result.details, "udpResponseSize", analysis.response_size.to_string());
    push_detail(&mut result.details, "udpAaFlag", analysis.aa_flag.to_string());
    push_detail(&mut result.details, "udpRcode", analysis.rcode.to_string());
    push_detail(&mut result.details, "udpAnswerCount", analysis.answer_count.to_string());
    push_detail(&mut result.details, "udpAuthorityCount", analysis.authority_count.to_string());
    push_detail(&mut result.details, "udpAdditionalCount", analysis.additional_count.to_string());
    push_detail(&mut result.details, "udpMinTtl", analysis.min_ttl.map_or_else(String::new, |value| value.to_string()));
    push_detail(&mut result.details, "udpMaxTtl", analysis.max_ttl.map_or_else(String::new, |value| value.to_string()));
    push_detail(&mut result.details, "udpHasEdns0", analysis.has_edns0.to_string());
    push_joined_string_detail(&mut result.details, "udpCnameTargets", &analysis.cname_targets);
    push_detail(&mut result.details, "udpTamperingScore", analysis.tampering_score.to_string());
    push_joined_str_detail(&mut result.details, "udpAnomalySignals", &analysis.signals);
    push_detail(&mut result.details, "malformedPointers", analysis.malformed_pointers.to_string());
}

#[inline(never)]
pub(super) fn append_record_comparison_details(result: &mut ProbeResult, udp_raw: &[u8], enc_raw: &[u8]) {
    let udp_records = parse_record_set(udp_raw);
    let enc_records = parse_record_set(enc_raw);
    let comparison = compare_dns_responses(&udp_records, &enc_records);

    let udp_types: Vec<&str> = udp_records.answers.iter().map(|r| r.rtype_name).collect();
    let enc_types: Vec<&str> = enc_records.answers.iter().map(|r| r.rtype_name).collect();

    push_detail(&mut result.details, "udpRecordTypes", udp_types.join("|"));
    push_detail(&mut result.details, "encryptedRecordTypes", enc_types.join("|"));
    push_detail(&mut result.details, "recordTypeMismatch", comparison.record_type_mismatch.to_string());
    push_detail(&mut result.details, "answerCountDivergence", comparison.answer_count_divergence.to_string());
    push_detail(
        &mut result.details,
        "ttlDivergence",
        comparison.ttl_divergence.map_or_else(String::new, |value| value.to_string()),
    );
    push_detail(&mut result.details, "authorityMismatch", comparison.authority_mismatch.to_string());
    push_joined_string_detail(&mut result.details, "extraCnames", &comparison.extra_cnames);
    push_detail(&mut result.details, "comparisonScore", comparison.comparison_score.to_string());
    push_joined_str_detail(&mut result.details, "comparisonSignals", &comparison.comparison_signals);
}
