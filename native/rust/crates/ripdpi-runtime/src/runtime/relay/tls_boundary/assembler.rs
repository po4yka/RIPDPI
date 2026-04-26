use ripdpi_config::RuntimeConfig;

use crate::runtime_policy::is_tls_client_hello_payload;

use super::FIRST_TLS_CLIENT_HELLO_BYTES_LIMIT;

#[derive(Default)]
pub(crate) struct TlsRecordBoundaryTracker {
    enabled: bool,
    disabled: bool,
    record_pos: usize,
    record_size: usize,
    header: [u8; 5],
    total_bytes: usize,
    bytes_limit: usize,
}

impl TlsRecordBoundaryTracker {
    pub(crate) fn for_first_response(request: &[u8], config: &RuntimeConfig) -> Self {
        if !is_tls_client_hello_payload(request) || config.timeouts.partial_timeout_ms == 0 {
            return Self::default();
        }
        Self::enabled(first_response_bytes_limit(config))
    }

    fn enabled(bytes_limit: usize) -> Self {
        Self { enabled: true, bytes_limit, ..Self::default() }
    }

    pub(crate) fn looks_like_client_hello_prefix(bytes: &[u8]) -> bool {
        match bytes {
            [0x16] | [0x16, 0x03] => true,
            [0x16, 0x03, minor, ..] => *minor <= 0x04,
            _ => false,
        }
    }

    pub(crate) fn active(&self) -> bool {
        self.enabled && !self.disabled
    }

    pub(crate) fn waiting_for_tls_record(&self) -> bool {
        self.active() && self.record_pos != 0 && self.record_pos != self.record_size
    }

    pub(crate) fn observe(&mut self, bytes: &[u8]) {
        if !self.active() {
            return;
        }

        self.total_bytes += bytes.len();
        if self.bytes_limit != 0 && self.total_bytes > self.bytes_limit {
            self.disabled = true;
            return;
        }

        let mut pos = 0usize;
        while pos < bytes.len() {
            if self.record_pos < 5 {
                self.header[self.record_pos] = bytes[pos];
                self.record_pos += 1;
                pos += 1;
                if self.record_pos < 5 {
                    continue;
                }
                self.record_size = usize::from(u16::from_be_bytes([self.header[3], self.header[4]])) + 5;
                if !valid_tls_record_header(self.header) {
                    self.disabled = true;
                    return;
                }
            }

            if self.record_pos == self.record_size {
                self.record_pos = 0;
                self.record_size = 0;
                continue;
            }

            let remaining = self.record_size.saturating_sub(self.record_pos);
            if remaining == 0 {
                self.disabled = true;
                return;
            }
            let take = remaining.min(bytes.len() - pos);
            self.record_pos += take;
            pos += take;
        }
    }
}

fn first_response_bytes_limit(config: &RuntimeConfig) -> usize {
    match usize::try_from(config.timeouts.timeout_bytes_limit) {
        Ok(limit) if limit != 0 => limit,
        _ => FIRST_TLS_CLIENT_HELLO_BYTES_LIMIT,
    }
}

pub(super) fn valid_tls_record_header(header: [u8; 5]) -> bool {
    let rec_type = header[0];
    (0x14..=0x18).contains(&rec_type) && header[1] == 0x03 && header[2] <= 0x04
}
