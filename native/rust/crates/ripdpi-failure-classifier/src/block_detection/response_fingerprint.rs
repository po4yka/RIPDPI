use super::fingerprint_catalog::{BlockpageFingerprint, FingerprintLocation, PatternType};

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
                .map(|(_, value)| value.to_lowercase())
                .unwrap_or_default(),
        };
        let pattern = fingerprint.pattern.to_lowercase();
        let matched = match fingerprint.pattern_type {
            PatternType::Full => haystack == pattern,
            PatternType::Prefix => haystack.starts_with(&pattern),
            PatternType::Contains => haystack.contains(&pattern),
        };
        matched.then(|| fingerprint.name.clone())
    })
}
