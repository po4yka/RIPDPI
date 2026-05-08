use ripdpi_packets::{tls_fake_profile_bytes, tune_tls_padding_size_into, TlsFakeProfile};
use std::time::{Duration, Instant};

pub const DEFAULT_FAKE_TLS: &[u8] = ripdpi_packets::DEFAULT_FAKE_TLS;
pub const IS_HTTPS: u32 = ripdpi_packets::IS_HTTPS;
pub const FIRST_TLS_CLIENT_HELLO_ASSEMBLY_TIMEOUT: Duration = Duration::from_millis(75);
pub const FIRST_TLS_CLIENT_HELLO_BYTES_LIMIT: usize = 16_384;

/// Build a TLS ClientHello with the given domain as SNI.
///
/// Uses the Google Chrome fake TLS profile, which the SNI replacement function
/// handles reliably, and patches in the probe domain.
pub fn build_probe_client_hello(domain: &str) -> Vec<u8> {
    let template = tls_fake_profile_bytes(TlsFakeProfile::GoogleChrome);
    let capacity = template.len() + domain.len() + 64;
    let mutation = ripdpi_packets::change_tls_sni_seeded_like_c(template, domain.as_bytes(), capacity, 0);
    let mut output = if mutation.rc == 0 {
        mutation.bytes
    } else {
        // If SNI patching fails, use the template as-is. This still exercises
        // the desync pipeline, just with the template SNI.
        template.to_vec()
    };
    avoid_tls_517_size(&mut output);
    output
}

pub fn payload_has_ech(payload: &[u8]) -> bool {
    ripdpi_packets::tls_marker_info(payload).and_then(|markers| markers.ech_ext_start).is_some()
}

pub fn group_accepts_any_or_non_http_tls(group: &ripdpi_config::DesyncGroup) -> bool {
    group.matches.any_protocol
        || group.matches.proto == 0
        || (group.matches.proto & (ripdpi_packets::IS_HTTP | ripdpi_packets::IS_HTTPS)) == 0
}

#[derive(Default)]
pub struct TlsRecordBoundaryTracker {
    enabled: bool,
    disabled: bool,
    record_pos: usize,
    record_size: usize,
    header: [u8; 5],
    total_bytes: usize,
    bytes_limit: usize,
}

#[derive(Default)]
pub struct FirstResponseBoundaryTracker {
    tracker: TlsRecordBoundaryTracker,
}

impl FirstResponseBoundaryTracker {
    pub fn for_request(request: &[u8], settings: crate::model::config::FirstResponseSettings) -> Self {
        Self { tracker: TlsRecordBoundaryTracker::for_first_response(request, settings) }
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
}

impl TlsRecordBoundaryTracker {
    pub fn for_first_response(request: &[u8], settings: crate::model::config::FirstResponseSettings) -> Self {
        if !crate::model::session::is_tls_client_hello_payload(request) || settings.partial_timeout_ms == 0 {
            return Self::default();
        }
        Self::enabled(crate::model::config::first_response_bytes_limit(settings, FIRST_TLS_CLIENT_HELLO_BYTES_LIMIT))
    }

    fn enabled(bytes_limit: usize) -> Self {
        Self { enabled: true, bytes_limit, ..Self::default() }
    }

    pub fn looks_like_client_hello_prefix(bytes: &[u8]) -> bool {
        match bytes {
            [0x16] | [0x16, 0x03] => true,
            [0x16, 0x03, minor, ..] => *minor <= 0x04,
            _ => false,
        }
    }

    pub fn active(&self) -> bool {
        self.enabled && !self.disabled
    }

    pub fn waiting_for_tls_record(&self) -> bool {
        self.active() && self.record_pos != 0 && self.record_pos != self.record_size
    }

    pub fn observe(&mut self, bytes: &[u8]) {
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

pub fn valid_tls_record_header(header: [u8; 5]) -> bool {
    let rec_type = header[0];
    (0x14..=0x18).contains(&rec_type) && header[1] == 0x03 && header[2] <= 0x04
}

pub struct OutboundTlsClientHelloAssembler {
    tracker: Option<TlsClientHelloBoundaryTracker>,
    buffer: Vec<u8>,
    started_at: Option<Instant>,
}

impl OutboundTlsClientHelloAssembler {
    pub fn new() -> Self {
        Self { tracker: None, buffer: Vec::new(), started_at: None }
    }

    pub fn push(&mut self, chunk: &[u8], now: Instant) -> Option<Vec<u8>> {
        if self.buffer.is_empty() && self.tracker.is_none() {
            if !TlsRecordBoundaryTracker::looks_like_client_hello_prefix(chunk) {
                return Some(chunk.to_vec());
            }
            self.tracker = Some(TlsClientHelloBoundaryTracker::enabled(FIRST_TLS_CLIENT_HELLO_BYTES_LIMIT));
            self.started_at = Some(now);
        }

        self.buffer.extend_from_slice(chunk);
        let tracker = self.tracker.as_mut().expect("tls client hello tracker");
        tracker.observe(chunk);
        if !tracker.active() {
            return self.take();
        }
        None
    }

    pub fn timeout(&self, now: Instant) -> Option<Duration> {
        let started_at = self.started_at?;
        self.is_buffering()
            .then(|| FIRST_TLS_CLIENT_HELLO_ASSEMBLY_TIMEOUT.saturating_sub(now.saturating_duration_since(started_at)))
            .map(|timeout| timeout.max(Duration::from_millis(1)))
    }

    pub fn flush_on_timeout(&mut self, now: Instant) -> Option<Vec<u8>> {
        let started_at = self.started_at?;
        (self.is_buffering() && now.saturating_duration_since(started_at) >= FIRST_TLS_CLIENT_HELLO_ASSEMBLY_TIMEOUT)
            .then(|| self.take())
            .flatten()
    }

    pub fn finish(&mut self) -> Option<Vec<u8>> {
        self.take()
    }

    pub fn is_buffering(&self) -> bool {
        !self.buffer.is_empty()
            && self.tracker.as_ref().is_some_and(TlsClientHelloBoundaryTracker::waiting_for_client_hello)
    }

    fn take(&mut self) -> Option<Vec<u8>> {
        self.tracker = None;
        self.started_at = None;
        (!self.buffer.is_empty()).then(|| std::mem::take(&mut self.buffer))
    }
}

impl Default for OutboundTlsClientHelloAssembler {
    fn default() -> Self {
        Self::new()
    }
}

struct TlsClientHelloBoundaryTracker {
    enabled: bool,
    disabled: bool,
    total_bytes: usize,
    bytes_limit: usize,
    record_header: [u8; 5],
    record_header_pos: usize,
    record_payload_remaining: usize,
    handshake_header: [u8; 4],
    handshake_header_pos: usize,
    handshake_bytes_seen: usize,
    handshake_total: Option<usize>,
}

impl TlsClientHelloBoundaryTracker {
    fn enabled(bytes_limit: usize) -> Self {
        Self {
            enabled: true,
            bytes_limit,
            disabled: false,
            total_bytes: 0,
            record_header: [0; 5],
            record_header_pos: 0,
            record_payload_remaining: 0,
            handshake_header: [0; 4],
            handshake_header_pos: 0,
            handshake_bytes_seen: 0,
            handshake_total: None,
        }
    }

    fn active(&self) -> bool {
        self.enabled && !self.disabled && !self.client_hello_complete()
    }

    fn waiting_for_client_hello(&self) -> bool {
        self.active() && self.total_bytes != 0
    }

    fn client_hello_complete(&self) -> bool {
        self.handshake_total.is_some_and(|total| self.handshake_bytes_seen >= total)
    }

    fn observe(&mut self, bytes: &[u8]) {
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
            if self.record_header_pos < 5 {
                self.record_header[self.record_header_pos] = bytes[pos];
                self.record_header_pos += 1;
                pos += 1;
                if self.record_header_pos < 5 {
                    continue;
                }
                if !valid_tls_record_header(self.record_header) {
                    self.disabled = true;
                    return;
                }
                self.record_payload_remaining =
                    usize::from(u16::from_be_bytes([self.record_header[3], self.record_header[4]]));
                if self.record_payload_remaining == 0 {
                    self.disabled = true;
                    return;
                }
                if self.handshake_total.is_some() && self.record_header[0] != 0x16 {
                    self.disabled = true;
                    return;
                }
            }

            let take = self.record_payload_remaining.min(bytes.len() - pos);
            if take == 0 {
                self.disabled = true;
                return;
            }
            self.observe_handshake_bytes(&bytes[pos..pos + take]);
            if !self.active() {
                return;
            }
            self.record_payload_remaining -= take;
            pos += take;
            if self.record_payload_remaining == 0 {
                self.record_header_pos = 0;
            }
        }
    }

    fn observe_handshake_bytes(&mut self, bytes: &[u8]) {
        let mut pos = 0usize;

        while pos < bytes.len() && self.handshake_header_pos < 4 {
            self.handshake_header[self.handshake_header_pos] = bytes[pos];
            self.handshake_header_pos += 1;
            self.handshake_bytes_seen += 1;
            pos += 1;
        }

        if self.handshake_header_pos < 4 {
            return;
        }

        if self.handshake_total.is_none() {
            if self.handshake_header[0] != 0x01 {
                self.disabled = true;
                return;
            }
            let handshake_len = ((self.handshake_header[1] as usize) << 16)
                | ((self.handshake_header[2] as usize) << 8)
                | (self.handshake_header[3] as usize);
            self.handshake_total = Some(handshake_len.saturating_add(4));
        }

        let Some(total) = self.handshake_total else {
            return;
        };
        let remaining = total.saturating_sub(self.handshake_bytes_seen);
        let take = remaining.min(bytes.len().saturating_sub(pos));
        self.handshake_bytes_seen += take;
    }
}

fn avoid_tls_517_size(output: &mut Vec<u8>) {
    if output.len() == 517 && ripdpi_packets::is_tls_client_hello(output) {
        let _ = tune_tls_padding_size_into(output, 518);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn build_probe_client_hello_produces_valid_tls_record() {
        let hello = build_probe_client_hello("youtube.com");
        assert!(ripdpi_packets::is_tls_client_hello(&hello), "probe payload must be a TLS ClientHello");
    }

    #[test]
    fn build_probe_client_hello_embeds_domain_as_sni() {
        let hello = build_probe_client_hello("discord.com");
        let sni = ripdpi_packets::parse_tls(&hello);
        assert!(sni.is_some(), "SNI should be extractable after patching");
        assert_eq!(sni.unwrap().len(), "discord.com".len(), "SNI length must match domain");
    }

    #[test]
    fn avoid_tls_517_size_retunes_padding_when_needed() {
        let mut hello = tls_fake_profile_bytes(TlsFakeProfile::GoogleChrome).to_vec();
        tune_tls_padding_size_into(&mut hello, 517);
        assert_eq!(hello.len(), 517);

        avoid_tls_517_size(&mut hello);

        assert_ne!(hello.len(), 517);
        assert!(ripdpi_packets::is_tls_client_hello(&hello));
    }
}
