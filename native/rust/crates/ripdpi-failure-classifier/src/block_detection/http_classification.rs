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
/// Words that name a site in an "<site> is blocked" sentence.
const BLOCKED_SUBJECTS_EN: &[&str] = &["site", "website", "webpage", "page", "resource", "url", "domain"];
/// Russian stems for the same subjects; Russian has no copula ("Доступ к ресурсу ограничен").
const BLOCKED_SUBJECT_STEMS_RU: &[&str] = &["сайт", "ресурс", "страниц", "доступ"];
const LINKING_WORDS: &[&str] = &[
    "is",
    "are",
    "was",
    "has",
    "have",
    "been",
    "currently",
    "temporarily",
    "permanently",
    "был",
    "была",
    "было",
    "временно",
];
const BLOCK_VERBS: &[&str] = &[
    "blocked",
    "restricted",
    "denied",
    "censored",
    "заблокирован",
    "заблокирована",
    "заблокировано",
    "ограничен",
    "ограничена",
    "ограничено",
];
/// Location host fragments that name an ISP block or warning stub, such as `blocked.isp.example`
/// or `lawfilter.isp.example`.
const BLOCK_STUB_HOST_FRAGMENTS: &[&str] = &["block", "filter", "warning"];
/// Location path segment words that name a stub page, such as `/blocked.html` or `/block-page`.
const BLOCK_STUB_PATH_WORDS: &[&str] = &["block", "blocked", "blockpage", "blocklist", "filter", "filtered", "warning"];

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

/// Check if the redirect Location names a block stub: a bundled fingerprint anywhere in the URL
/// (such as `domru.ru/block`), a host label that contains a stub word, or a path segment word that
/// is one. Queries and fragments do not count, and neither does a relative Location, which stays
/// on the requested site.
fn match_redirect_block(headers: &[(String, String)]) -> Option<String> {
    let location = headers
        .iter()
        .find(|(name, _)| name.eq_ignore_ascii_case("location"))
        .map(|(_, value)| value.trim().to_lowercase())?;
    let rest = location.split_once("//").map(|(_, rest)| rest)?;
    if let Some(provider) = match_blockpage_response(&[], rest.as_bytes(), bundled_blockpage_fingerprints()) {
        return Some(provider);
    }
    let url = rest.split(['?', '#']).next().unwrap_or_default();
    let (authority, path) = url.split_once('/').unwrap_or((url, ""));
    let host = authority.rsplit('@').next().unwrap_or_default();
    let host = host.split(':').next().unwrap_or_default();
    let host_hit = host.split('.').any(|label| BLOCK_STUB_HOST_FRAGMENTS.iter().any(|word| label.contains(word)));
    let path_hit = path.split(|c: char| !c.is_alphanumeric()).any(|word| BLOCK_STUB_PATH_WORDS.contains(&word));
    (host_hit || path_hit).then(|| "redirect_block_url".to_string())
}

/// Match an explicit third-party block message in the response body (case-insensitive): a known
/// sentence, or a site subject followed by a block verb ("the requested resource is blocked",
/// "Доступ к ресурсу ограничен"). A bare "blocked" or "forbidden" is not evidence.
pub(crate) fn match_body_keyword(body: &[u8]) -> Option<String> {
    if body.len() > MAX_EXPLICIT_BLOCKPAGE_BODY {
        return None;
    }
    let body_lower = String::from_utf8_lossy(body).to_lowercase();
    (EXPLICIT_BLOCKPAGE_MESSAGES.iter().any(|message| body_lower.contains(message))
        || states_site_is_blocked(&body_lower))
    .then(|| "explicit_blockpage_message".to_string())
}

fn states_site_is_blocked(text: &str) -> bool {
    let words: Vec<&str> = text.split(|c: char| !c.is_alphanumeric()).filter(|word| !word.is_empty()).collect();
    words.iter().enumerate().any(|(index, word)| {
        let english = BLOCKED_SUBJECTS_EN.contains(word);
        if !english && !BLOCKED_SUBJECT_STEMS_RU.iter().any(|stem| word.starts_with(stem)) {
            return false;
        }
        let after = &words[index + 1..];
        let gap = after.iter().take(3).take_while(|word| LINKING_WORDS.contains(word)).count();
        // English needs a linking verb, so a label such as "This page: blocked." is not a sentence.
        (!english || gap > 0) && after.get(gap).is_some_and(|word| BLOCK_VERBS.contains(word))
    })
}
