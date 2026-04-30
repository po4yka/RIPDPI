use super::assembler::valid_tls_record_header;

pub(super) struct TlsClientHelloBoundaryTracker {
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
    pub(super) fn enabled(bytes_limit: usize) -> Self {
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

    pub(super) fn active(&self) -> bool {
        self.enabled && !self.disabled && !self.client_hello_complete()
    }

    pub(super) fn waiting_for_client_hello(&self) -> bool {
        self.active() && self.total_bytes != 0
    }

    fn client_hello_complete(&self) -> bool {
        self.handshake_total.is_some_and(|total| self.handshake_bytes_seen >= total)
    }

    pub(super) fn observe(&mut self, bytes: &[u8]) {
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
