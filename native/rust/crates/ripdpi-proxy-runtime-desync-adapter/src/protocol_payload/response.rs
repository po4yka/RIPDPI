use super::{TlsRecordBoundaryTracker, is_tls_client_hello_candidate};
use std::time::Duration;

const RESPONSE_INSPECTION_BYTES_LIMIT: usize = 16_384;

#[derive(Default)]
pub struct FirstResponseBoundaryTracker {
    tracker: TlsRecordBoundaryTracker,
    tls_request: bool,
    inspection_timeout: Option<Duration>,
    bytes_limit: usize,
}

impl FirstResponseBoundaryTracker {
    pub fn for_request(request: &[u8], settings: crate::model::config::FirstResponseSettings) -> Self {
        let tracker = TlsRecordBoundaryTracker::for_first_response(request, settings);
        let tls_request = is_tls_client_hello_candidate(request);
        let inspection_timeout = if tls_request {
            crate::model::config::first_response_timeout(
                crate::model::config::FirstResponseSettings { fallback_timeout_required: true, ..settings },
                tracker.active(),
            )
        } else {
            None
        };
        let bytes_limit = crate::model::config::first_response_bytes_limit(settings, RESPONSE_INSPECTION_BYTES_LIMIT)
            .clamp(1, RESPONSE_INSPECTION_BYTES_LIMIT);
        Self { tracker, tls_request, inspection_timeout, bytes_limit }
    }

    pub fn active(&self) -> bool {
        self.tracker.active()
    }

    pub fn waiting_for_tls_record(&self) -> bool {
        self.tracker.waiting_for_tls_record()
    }

    pub fn observe(&mut self, bytes: &[u8]) {
        self.tracker.observe(bytes);
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
        self.waiting_for_tls_record()
    }

    pub fn inspection_exhausted(&self, response: &[u8]) -> bool {
        self.inspecting_alert(response) && self.waiting_for_response(response) && response.len() >= self.bytes_limit
    }

    pub fn invalid_alert(&self, response: &[u8]) -> bool {
        self.inspecting_alert(response)
            && matches!(crate::failure::tls_alert_disposition(response), crate::failure::TlsAlertDisposition::Invalid)
    }
}
