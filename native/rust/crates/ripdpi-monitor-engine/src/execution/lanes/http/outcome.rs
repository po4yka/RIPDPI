use std::sync::LazyLock;

use crate::blockpage_fingerprints::{BlockpageFingerprint, load_fingerprints};
use crate::http::{HttpObservation, classify_http_response_with_fingerprints, is_blockpage};

static BLOCKPAGE_FINGERPRINTS: LazyLock<Vec<BlockpageFingerprint>> = LazyLock::new(load_fingerprints);

pub(super) fn classify_http_observation(observation: &HttpObservation) -> (String, Option<String>) {
    let Some(response) = &observation.response else {
        return if observation.error.is_some() {
            ("http_unreachable".to_string(), None)
        } else {
            (observation.status.clone(), None)
        };
    };
    let (fingerprint_outcome, fingerprint_name) =
        classify_http_response_with_fingerprints(response, &BLOCKPAGE_FINGERPRINTS);
    let outcome = if fingerprint_name.is_some() {
        fingerprint_outcome
    } else if is_blockpage(observation) {
        "http_blockpage".to_string()
    } else if observation.status == "http_ok" {
        "http_ok".to_string()
    } else if observation.status.starts_with("http_status_3") {
        "http_redirect".to_string()
    } else if observation.error.is_some() {
        "http_unreachable".to_string()
    } else {
        observation.status.clone()
    };
    (outcome, fingerprint_name)
}
