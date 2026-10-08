use crate::blockpage_fingerprints::{BlockpageFingerprint, match_blockpage};

use super::types::{HttpObservation, HttpResponse};

pub fn classify_http_response(response: &HttpResponse) -> String {
    let blockpage_body = if response.status_code == 403 {
        body_has_explicit_blockpage_message(&response.body)
    } else {
        body_has_blockpage_keywords(&response.body)
    };
    if response.status_code == 200 && !blockpage_body {
        "http_ok".to_string()
    } else if response.status_code == 451 || blockpage_body {
        "http_blockpage".to_string()
    } else {
        format!("http_status_{}", response.status_code)
    }
}

/// Classify an HTTP response using the fingerprint database first, then fall
/// back to the existing heuristic classification. Returns the classification
/// string and, when a fingerprint matched, its name.
pub fn classify_http_response_with_fingerprints(
    response: &HttpResponse,
    fingerprints: &[BlockpageFingerprint],
) -> (String, Option<String>) {
    if let Some(fp_name) = match_blockpage(response, fingerprints) {
        return ("http_blockpage".to_string(), Some(fp_name));
    }
    (classify_http_response(response), None)
}

pub fn describe_http_observation(observation: &HttpObservation) -> String {
    match (&observation.response, &observation.error) {
        (Some(response), _) => format!(
            "{} {} {}",
            response.status_code,
            response.reason,
            response.headers.get("server").cloned().unwrap_or_else(|| "server=unknown".to_string())
        ),
        (None, Some(error)) => error.clone(),
        (None, None) => "none".to_string(),
    }
}

pub fn is_blockpage(observation: &HttpObservation) -> bool {
    observation.status == "http_blockpage"
}

pub fn body_has_blockpage_keywords(body: &[u8]) -> bool {
    // Large pages (>8KB) from legitimate sites may contain censorship-related words
    // in their normal content; only flag short responses as potential blockpages.
    if body.len() > 8192 {
        return false;
    }
    let text = String::from_utf8_lossy(body).to_ascii_lowercase();
    ["blocked", "access denied", "forbidden", "restriction", "censorship"].iter().any(|needle| text.contains(needle))
}

fn body_has_explicit_blockpage_message(body: &[u8]) -> bool {
    if body.len() > 8192 {
        return false;
    }
    // Generic Forbidden and Access Denied pages also come from origin authorization.
    let text = String::from_utf8_lossy(body).to_ascii_lowercase();
    [
        "this site has been blocked",
        "this website has been blocked",
        "this page has been blocked",
        "blocked by the government",
        "blocked by your internet service provider",
        "blocked by your isp",
        "blocked by your provider",
        "blocked by your network",
    ]
    .iter()
    .any(|needle| text.contains(needle))
}
