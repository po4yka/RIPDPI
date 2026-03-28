//! OONI-style blockpage fingerprint matching.
//!
//! Structured patterns identify ISP-specific blockpages with lower false-positive
//! rates than simple keyword scanning. The fingerprint database is bundled as CSV
//! and compiled into the binary via `include_str!`.

use crate::http::HttpResponse;

#[derive(Debug, Clone)]
pub(crate) struct BlockpageFingerprint {
    pub(crate) name: String,
    pub(crate) location: FingerprintLocation,
    pub(crate) pattern_type: PatternType,
    pub(crate) pattern: String,
    #[allow(dead_code)] // metadata kept for reporting/filtering
    pub(crate) scope: String,
    #[allow(dead_code)] // metadata kept for reporting/filtering
    pub(crate) confidence: String,
}

#[derive(Debug, Clone)]
pub(crate) enum FingerprintLocation {
    Body,
    Header(String), // header name, lowercase
}

#[derive(Debug, Clone)]
pub(crate) enum PatternType {
    Full,
    Prefix,
    Contains,
}

/// Parse the bundled CSV into a list of fingerprints.
pub(crate) fn load_fingerprints() -> Vec<BlockpageFingerprint> {
    let csv = include_str!("../blockpage_fingerprints.csv");
    csv.lines()
        .skip(1) // header row
        .filter(|line| !line.trim().is_empty())
        .filter_map(parse_fingerprint_line)
        .collect()
}

fn parse_fingerprint_line(line: &str) -> Option<BlockpageFingerprint> {
    let parts: Vec<&str> = line.splitn(6, ',').collect();
    if parts.len() < 6 {
        return None;
    }
    let location = if parts[1] == "body" {
        FingerprintLocation::Body
    } else if let Some(header) = parts[1].strip_prefix("header.") {
        FingerprintLocation::Header(header.to_ascii_lowercase())
    } else {
        return None;
    };
    let pattern_type = match parts[2] {
        "full" => PatternType::Full,
        "prefix" => PatternType::Prefix,
        "contains" => PatternType::Contains,
        _ => return None,
    };
    Some(BlockpageFingerprint {
        name: parts[0].to_string(),
        location,
        pattern_type,
        pattern: parts[3].to_string(),
        scope: parts[4].to_string(),
        confidence: parts[5].to_string(),
    })
}

/// Match an HTTP response against the fingerprint database.
/// Returns the first matching fingerprint name, or `None`.
pub(crate) fn match_blockpage(response: &HttpResponse, fingerprints: &[BlockpageFingerprint]) -> Option<String> {
    for fp in fingerprints {
        let haystack = match &fp.location {
            FingerprintLocation::Body => String::from_utf8_lossy(&response.body).to_ascii_lowercase(),
            FingerprintLocation::Header(name) => {
                response.headers.get(name.as_str()).cloned().unwrap_or_default().to_ascii_lowercase()
            }
        };
        let pattern = fp.pattern.to_ascii_lowercase();
        let matched = match fp.pattern_type {
            PatternType::Full => haystack == pattern,
            PatternType::Prefix => haystack.starts_with(&pattern),
            PatternType::Contains => haystack.contains(&pattern),
        };
        if matched {
            return Some(fp.name.clone());
        }
    }
    None
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::HashMap;

    #[test]
    fn load_fingerprints_parses_bundled_csv() {
        let fps = load_fingerprints();
        assert!(!fps.is_empty());
        assert!(fps.iter().any(|f| f.name == "rkn_standard"));
    }

    #[test]
    fn load_fingerprints_parses_all_rows() {
        let fps = load_fingerprints();
        // CSV has 24 data rows
        assert_eq!(fps.len(), 24);
    }

    #[test]
    fn load_fingerprints_parses_header_location() {
        let fps = load_fingerprints();
        let mts = fps.iter().find(|f| f.name == "mts_header").unwrap();
        assert!(matches!(&mts.location, FingerprintLocation::Header(h) if h == "server"));
    }

    #[test]
    fn match_blockpage_detects_rkn() {
        let fps = load_fingerprints();
        let response = HttpResponse {
            status_code: 200,
            reason: "OK".to_string(),
            headers: HashMap::new(),
            body: b"<html>zapret-info.gov.ru blocked</html>".to_vec(),
        };
        assert_eq!(match_blockpage(&response, &fps), Some("rkn_standard".to_string()));
    }

    #[test]
    fn match_blockpage_detects_header_fingerprint() {
        let fps = load_fingerprints();
        let mut headers = HashMap::new();
        headers.insert("server".to_string(), "MTS Proxy/1.0".to_string());
        let response =
            HttpResponse { status_code: 200, reason: "OK".to_string(), headers, body: b"<html>OK</html>".to_vec() };
        assert_eq!(match_blockpage(&response, &fps), Some("mts_header".to_string()));
    }

    #[test]
    fn match_blockpage_returns_none_for_normal_page() {
        let fps = load_fingerprints();
        let response = HttpResponse {
            status_code: 200,
            reason: "OK".to_string(),
            headers: HashMap::new(),
            body: b"<html>Hello world, welcome to our website</html>".to_vec(),
        };
        assert_eq!(match_blockpage(&response, &fps), None);
    }

    #[test]
    fn match_blockpage_is_case_insensitive() {
        let fps = load_fingerprints();
        let response = HttpResponse {
            status_code: 200,
            reason: "OK".to_string(),
            headers: HashMap::new(),
            body: b"<html>ZAPRET-INFO.GOV.RU</html>".to_vec(),
        };
        assert_eq!(match_blockpage(&response, &fps), Some("rkn_standard".to_string()));
    }
}
