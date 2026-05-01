use super::response::DnsResponseAnalysis;

const WEIGHT_AA_ON_RECURSIVE: u32 = 15;
const WEIGHT_NO_AUTHORITY: u32 = 10;
const WEIGHT_NO_ADDITIONAL: u32 = 10;
const WEIGHT_SUSPICIOUS_TTL: u32 = 15;
const WEIGHT_NO_EDNS0: u32 = 10;
const WEIGHT_SMALL_RESPONSE: u32 = 10;
const WEIGHT_SINGLE_ANSWER: u32 = 5;
const WEIGHT_MALFORMED_POINTERS: u32 = 15;

pub(super) fn compute_tampering_score(analysis: &mut DnsResponseAnalysis) {
    let mut score: u32 = 0;

    if analysis.aa_flag {
        score += WEIGHT_AA_ON_RECURSIVE;
        analysis.signals.push("aa_on_recursive");
    }

    if analysis.authority_count == 0 {
        score += WEIGHT_NO_AUTHORITY;
        analysis.signals.push("no_authority");
    }

    if analysis.additional_count == 0 && !analysis.has_edns0 {
        score += WEIGHT_NO_ADDITIONAL;
        analysis.signals.push("no_additional");
    }

    if let Some(min) = analysis.min_ttl {
        let is_round = |ttl: u32| matches!(ttl, 0 | 300 | 600 | 3600 | 7200 | 86400);
        if min == 0 || (analysis.ttl_uniform && is_round(min)) {
            score += WEIGHT_SUSPICIOUS_TTL;
            analysis.signals.push("suspicious_ttl");
        }
    }

    if !analysis.has_edns0 && analysis.response_size >= 12 {
        score += WEIGHT_NO_EDNS0;
        analysis.signals.push("no_edns0");
    }

    if analysis.response_size > 0 && analysis.response_size < 64 {
        score += WEIGHT_SMALL_RESPONSE;
        analysis.signals.push("small_response");
    }

    if analysis.answer_count == 1 {
        score += WEIGHT_SINGLE_ANSWER;
        analysis.signals.push("single_answer");
    }

    if analysis.malformed_pointers {
        score += WEIGHT_MALFORMED_POINTERS;
        analysis.signals.push("malformed_pointers");
    }

    analysis.tampering_score = score.min(100);
}
