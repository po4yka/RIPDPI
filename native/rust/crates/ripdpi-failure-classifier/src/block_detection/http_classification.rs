use crate::{ClassifiedFailure, FailureAction, FailureClass, FailureStage};

use super::fingerprint_catalog::bundled_blockpage_fingerprints;
use super::http_parse::parse_http_response;
use super::response_fingerprint::match_blockpage_response;

/// Explicit third-party block messages that count as a blockpage when no CSV
/// fingerprint matches. Generic words such as "blocked", "forbidden" or
/// "access denied" also appear on origin authorization, WAF and error pages,
/// so they are not evidence. Checked case-insensitively.
const EXPLICIT_BLOCKPAGE_MESSAGES: &[&str] = &[
    "this site has been blocked",
    "this website has been blocked",
    "this page has been blocked",
    "blocked by the government",
    "blocked by your internet service provider",
    "blocked by your isp",
    "blocked by your provider",
    "blocked by your network",
];
/// Large pages from legitimate sites may quote a block message in normal content.
const MAX_EXPLICIT_BLOCKPAGE_BODY: usize = 8192;
/// Host labels that name an ISP block or warning stub, such as `blocked.isp.example`.
const BLOCK_STUB_HOST_LABELS: &[&str] = &["block", "blocked", "filter", "warning"];

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

/// Check if the redirect Location host has a label that names a block stub.
/// Only the host counts: paths and queries such as `/blockchain` or
/// `?filter=x` are ordinary site URLs.
fn match_redirect_block(headers: &[(String, String)]) -> Option<String> {
    let location = headers
        .iter()
        .find(|(name, _)| name.eq_ignore_ascii_case("location"))
        .map(|(_, value)| value.trim().to_ascii_lowercase())?;
    let authority = location.split_once("//").map(|(_, rest)| rest)?;
    let authority = authority.split(['/', '?', '#']).next().unwrap_or_default();
    let host = authority.rsplit('@').next().unwrap_or_default();
    let host = host.split(':').next().unwrap_or_default();
    host.split('.').any(|label| BLOCK_STUB_HOST_LABELS.contains(&label)).then(|| "redirect_block_url".to_string())
}

/// Match an explicit third-party block message in the response body (case-insensitive).
pub(crate) fn match_body_keyword(body: &[u8]) -> Option<String> {
    if body.len() > MAX_EXPLICIT_BLOCKPAGE_BODY {
        return None;
    }
    let body_lower = String::from_utf8_lossy(body).to_lowercase();
    EXPLICIT_BLOCKPAGE_MESSAGES
        .iter()
        .any(|message| body_lower.contains(message))
        .then(|| "explicit_blockpage_message".to_string())
}
