use crate::{ClassifiedFailure, FailureAction, FailureClass, FailureStage};

use super::fingerprint_catalog::bundled_blockpage_fingerprints;
use super::http_parse::parse_http_response;
use super::response_fingerprint::match_blockpage_response;

/// Generic body keywords that indicate a blockpage when no CSV fingerprint
/// matches. Checked case-insensitively.
const BLOCKPAGE_KEYWORDS: &[&str] = &["blocked", "access denied", "forbidden", "restriction", "censorship"];

pub fn classify_http_response_block(response: &[u8]) -> Option<ClassifiedFailure> {
    let response = parse_http_response(response)?;
    if response.status_code == 429 {
        return None;
    }

    let provider = match_blockpage_response(&response.headers, &response.body, bundled_blockpage_fingerprints());
    let is_redirect = (300..400).contains(&response.status_code);

    if is_redirect {
        if let Some(provider) = &provider {
            return Some(
                ClassifiedFailure::new(
                    FailureClass::Redirect,
                    FailureStage::FirstResponse,
                    FailureAction::RetryWithMatchingGroup,
                    format!("HTTP redirect matched known block fingerprint: {provider}"),
                )
                .with_tag("status", response.status_code.to_string())
                .with_tag("provider", provider.clone()),
            );
        }
        let url_provider = match_redirect_block(&response.headers)?;
        return Some(
            ClassifiedFailure::new(
                FailureClass::HttpBlockpage,
                FailureStage::FirstResponse,
                FailureAction::RetryWithMatchingGroup,
                format!("HTTP redirect to block URL: {url_provider}"),
            )
            .with_tag("status", response.status_code.to_string())
            .with_tag("provider", url_provider),
        );
    }

    // Status 451 (Unavailable For Legal Reasons) is always a blockpage.
    if response.status_code == 451 {
        let mut failure = ClassifiedFailure::new(
            FailureClass::HttpBlockpage,
            FailureStage::HttpResponse,
            FailureAction::RetryWithMatchingGroup,
            "HTTP 451 Unavailable For Legal Reasons".to_string(),
        )
        .with_tag("status", "451".to_string());
        if let Some(provider) = provider {
            failure = failure.with_tag("provider", provider);
        }
        return Some(failure);
    }

    // Try CSV fingerprint first, then fall back to generic keyword matching.
    let provider = provider.filter(|value| !value.trim().is_empty()).or_else(|| match_body_keyword(&response.body));
    provider.as_ref()?;

    let mut failure = ClassifiedFailure::new(
        FailureClass::HttpBlockpage,
        FailureStage::HttpResponse,
        FailureAction::RetryWithMatchingGroup,
        format!("HTTP blockpage with status {}", response.status_code),
    )
    .with_tag("status", response.status_code.to_string());
    if let Some(provider) = provider {
        failure = failure.with_tag("provider", provider);
    }
    Some(failure)
}

/// Check if the redirect Location header points to a known block domain.
fn match_redirect_block(headers: &[(String, String)]) -> Option<String> {
    let location = headers
        .iter()
        .find(|(name, _)| name.eq_ignore_ascii_case("location"))
        .map(|(_, value)| value.to_lowercase())?;
    if location.contains("block") || location.contains("filter") || location.contains("warning") {
        return Some("redirect_block_url".to_string());
    }
    None
}

/// Match generic blockpage keywords in the response body (case-insensitive).
pub(crate) fn match_body_keyword(body: &[u8]) -> Option<String> {
    let body_lower = String::from_utf8_lossy(body).to_lowercase();
    BLOCKPAGE_KEYWORDS
        .iter()
        .find(|keyword| body_lower.contains(**keyword))
        .map(|keyword| format!("keyword_{}", keyword.replace(' ', "_")))
}
