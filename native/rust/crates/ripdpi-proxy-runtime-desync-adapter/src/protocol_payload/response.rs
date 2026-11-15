use super::{TlsRecordBoundaryTracker, is_tls_client_hello_candidate};
use std::time::Duration;

const RESPONSE_INSPECTION_BYTES_LIMIT: usize = 16_384;

#[derive(Default)]
pub struct FirstResponseBoundaryTracker {
    tracker: TlsRecordBoundaryTracker,
    tls_request: bool,
    http: super::http_response::HttpResponseBoundary,
    inspection_timeout: Option<Duration>,
    bytes_limit: usize,
}

impl FirstResponseBoundaryTracker {
    pub fn for_request(request: &[u8], settings: crate::model::config::FirstResponseSettings) -> Self {
        let tracker = TlsRecordBoundaryTracker::for_first_response(request, settings);
        let tls_request = is_tls_client_hello_candidate(request);
        let http_eligible = tls_request || ripdpi_packets::is_http_request_prefix(request);
        let http = super::http_response::HttpResponseBoundary::new(request, http_eligible);
        let inspection_timeout = if http_eligible {
            crate::model::config::first_response_timeout(
                crate::model::config::FirstResponseSettings { fallback_timeout_required: true, ..settings },
                tracker.active(),
            )
        } else {
            None
        };
        let bytes_limit = crate::model::config::first_response_bytes_limit(settings, RESPONSE_INSPECTION_BYTES_LIMIT)
            .clamp(1, RESPONSE_INSPECTION_BYTES_LIMIT);
        Self { tracker, tls_request, http, inspection_timeout, bytes_limit }
    }

    pub fn active(&self) -> bool {
        self.tracker.active()
    }

    pub fn waiting_for_tls_record(&self) -> bool {
        self.tracker.waiting_for_tls_record()
    }

    pub fn observe(&mut self, bytes: &[u8]) {
        self.tracker.observe(bytes);
        self.http.observe(bytes);
    }

    pub fn inspection_timeout(&self) -> Option<Duration> {
        self.inspection_timeout
    }

    pub fn inspection_bytes_limit(&self) -> usize {
        self.bytes_limit
    }

    pub fn inspecting_alert(&self, response: &[u8]) -> bool {
        self.tls_request && response.first() == Some(&0x15)
    }

    pub fn waiting_for_response(&self, response: &[u8]) -> bool {
        if self.inspecting_alert(response) {
            return matches!(
                crate::failure::tls_alert_disposition(response),
                crate::failure::TlsAlertDisposition::Incomplete
                    | crate::failure::TlsAlertDisposition::WarningPending(_)
            );
        }
        self.http.waiting() || self.waiting_for_tls_record()
    }

    pub fn inspection_exhausted(&self, response: &[u8]) -> bool {
        self.inspecting_response(response) && self.waiting_for_response(response) && response.len() >= self.bytes_limit
    }

    pub fn inspecting_response(&self, response: &[u8]) -> bool {
        self.inspecting_alert(response) || self.http.active()
    }

    pub fn inspecting_http(&self) -> bool {
        self.http.active()
    }

    pub fn neutral_http(&self) -> bool {
        self.http.neutral()
    }

    pub fn finish_http_on_eof(&mut self) -> bool {
        self.http.finish_on_eof()
    }

    pub fn classification_bytes<'a>(&self, response: &'a [u8], body_allowed: bool) -> Option<&'a [u8]> {
        self.http.classification_bytes(response, body_allowed)
    }

    pub fn invalid_alert(&self, response: &[u8]) -> bool {
        self.inspecting_alert(response)
            && matches!(crate::failure::tls_alert_disposition(response), crate::failure::TlsAlertDisposition::Invalid)
    }
}
