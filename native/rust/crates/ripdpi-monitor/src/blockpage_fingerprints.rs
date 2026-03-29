//! Compatibility wrapper over the shared blockpage fingerprint matcher.

use crate::http::HttpResponse;
pub(crate) use ripdpi_failure_classifier::{load_blockpage_fingerprints as load_fingerprints, BlockpageFingerprint};

pub(crate) fn match_blockpage(response: &HttpResponse, fingerprints: &[BlockpageFingerprint]) -> Option<String> {
    let headers = response.headers.iter().map(|(name, value)| (name.clone(), value.clone())).collect::<Vec<_>>();
    ripdpi_failure_classifier::match_blockpage_response(&headers, &response.body, fingerprints)
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_failure_classifier::FingerprintLocation;
    use std::collections::HashMap;

    #[test]
    fn load_fingerprints_parses_bundled_csv() {
        let fps = load_fingerprints();
        assert!(!fps.is_empty());
        assert!(fps.iter().any(|f| f.name == "rkn_standard"));
    }

    #[test]
    fn load_fingerprints_parses_header_location() {
        let fps = load_fingerprints();
        let mts = fps.iter().find(|f| f.name == "mts_header").expect("mts header fingerprint");
        assert!(matches!(&mts.location, FingerprintLocation::Header(h) if h == "server"));
    }

    #[test]
    fn match_blockpage_detects_shared_fingerprints() {
        let fps = load_fingerprints();
        let response = HttpResponse {
            status_code: 200,
            reason: "OK".to_string(),
            headers: HashMap::new(),
            body: b"<html>zapret-info.gov.ru blocked</html>".to_vec(),
        };
        assert_eq!(match_blockpage(&response, &fps), Some("rkn_standard".to_string()));
    }
}
