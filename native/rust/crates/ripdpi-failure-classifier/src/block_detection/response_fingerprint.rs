use super::fingerprint_catalog::{BlockpageFingerprint, FingerprintLocation, PatternType};
use super::http_classification::match_body_keyword;

pub fn match_blockpage_response(
    headers: &[(String, String)],
    body: &[u8],
    fingerprints: &[BlockpageFingerprint],
) -> Option<String> {
    fingerprints.iter().find_map(|fingerprint| {
        let haystack = match &fingerprint.location {
            FingerprintLocation::Body => String::from_utf8_lossy(body).to_lowercase(),
            FingerprintLocation::Header(name) => headers
                .iter()
                .find(|(header, _)| header.eq_ignore_ascii_case(name))
                .map_or_default(|(_, value)| value.to_lowercase()),
        };
        let pattern = fingerprint.pattern.to_lowercase();
        let matched = match fingerprint.pattern_type {
            PatternType::Full => haystack == pattern,
            PatternType::Prefix => haystack.starts_with(&pattern),
            PatternType::Contains => haystack.contains(&pattern),
        };
        if !matched {
            return None;
        }
        // Generic body text needs a short, explicit block message; an article can quote the same words.
        let confirmed = !matches!(fingerprint.location, FingerprintLocation::Body)
            || fingerprint.confidence == "high"
            || match_body_keyword(body).is_some();
        confirmed.then(|| fingerprint.name.clone())
    })
}
