// This is a bounded first-message inspector, not an HTTP endpoint or transaction engine.
#[derive(Default)]
pub(super) struct HttpResponseBoundary {
    eligible: bool,
    head_request: bool,
    connect_request: bool,
    incomplete_method: bool,
    prefix: Vec<u8>,
    total: usize,
    header_end: Option<usize>,
    body_end: Option<usize>,
    unsupported: bool,
    interim: bool,
    eof: bool,
    not_http: bool,
}

impl HttpResponseBoundary {
    pub(super) fn new(mut request: &[u8], eligible: bool) -> Self {
        // Match the packet request parser: it accepts at most two leading CR/LF bytes.
        for _ in 0..2 {
            if matches!(request.first(), Some(b'\r' | b'\n')) {
                request = &request[1..];
            } else {
                break;
            }
        }
        Self {
            eligible,
            head_request: request.starts_with(b"HEAD "),
            connect_request: request.starts_with(b"CONNECT "),
            incomplete_method: ripdpi_packets::is_http_request_prefix(request) && !request.contains(&b' '),
            ..Self::default()
        }
    }

    pub(super) fn observe(&mut self, bytes: &[u8]) {
        if !self.eligible || self.not_http {
            return;
        }
        self.total = self.total.saturating_add(bytes.len());
        if self.header_end.is_some() || self.unsupported {
            return;
        }
        let previous_len = self.prefix.len();
        self.prefix.extend_from_slice(bytes);
        let compared = self.prefix.len().min(5);
        if self.prefix[..compared] != b"HTTP/"[..compared] {
            self.not_http = true;
            self.prefix.clear();
            return;
        }
        let Some(relative) = self.prefix[previous_len.saturating_sub(3)..].windows(4).position(|w| w == b"\r\n\r\n")
        else {
            return;
        };
        let end = previous_len.saturating_sub(3) + relative + 4;
        self.header_end = Some(end);
        self.prefix.truncate(end);
        self.parse_headers(end);
        self.prefix.clear();
    }

    fn parse_headers(&mut self, end: usize) {
        // All invalid or unsupported framing stays neutral. Header evidence can still be used.
        self.unsupported = true;
        let Ok(head) = std::str::from_utf8(&self.prefix) else {
            return;
        };
        let mut lines = head.split("\r\n");
        let mut status = lines.next().unwrap_or_default().splitn(3, ' ');
        if !matches!(status.next(), Some("HTTP/1.0" | "HTTP/1.1")) {
            return;
        }
        let code = status.next().unwrap_or_default();
        if code.len() != 3 || !code.bytes().all(|b| b.is_ascii_digit()) {
            return;
        }
        let Ok(code) = code.parse::<u16>() else {
            return;
        };
        // An assembly timeout can emit only a method prefix. It cannot establish body framing.
        if self.incomplete_method {
            return;
        }
        let mut content_length = None;
        let mut encoded = false;
        let mut transfer = false;
        for line in lines.filter(|line| !line.is_empty()) {
            let Some((name, value)) = line.split_once(':') else {
                return;
            };
            if name.is_empty() || !name.bytes().all(|b| b.is_ascii_alphanumeric() || b"!#$%&'*+-.^_`|~".contains(&b)) {
                return;
            }
            let value = value.trim_matches([' ', '\t']);
            if name.eq_ignore_ascii_case("content-length") {
                for length in value.split(',').map(|length| length.trim_matches([' ', '\t'])) {
                    if length.is_empty() || !length.bytes().all(|b| b.is_ascii_digit()) {
                        return;
                    }
                    let Ok(length) = length.parse::<usize>() else {
                        return;
                    };
                    if content_length.is_some_and(|previous| previous != length) {
                        return;
                    }
                    content_length = Some(length);
                }
            } else if name.eq_ignore_ascii_case("transfer-encoding") {
                transfer = true;
            } else if name.eq_ignore_ascii_case("content-encoding") && !value.eq_ignore_ascii_case("identity") {
                encoded = true;
            }
        }
        self.interim = (100..200).contains(&code);
        if self.head_request
            || self.interim
            || matches!(code, 204 | 304)
            || (self.connect_request && (200..300).contains(&code))
        {
            self.body_end = Some(end);
            self.unsupported = false;
            return;
        }
        if transfer || encoded {
            return;
        }
        self.body_end = match content_length {
            Some(length) => match end.checked_add(length) {
                Some(end) => Some(end),
                None => return,
            },
            None => None,
        };
        self.unsupported = false;
    }

    pub(super) fn active(&self) -> bool {
        self.eligible && !self.not_http && self.total != 0
    }

    pub(super) fn waiting(&self) -> bool {
        self.active()
            && !self.unsupported
            && (self.header_end.is_none() || self.body_end.is_none_or(|end| self.total < end) && !self.eof)
    }

    pub(super) fn neutral(&self) -> bool {
        self.active() && (self.unsupported || self.interim || self.waiting())
    }

    pub(super) fn finish_on_eof(&mut self) -> bool {
        if self.active() && self.header_end.is_some() && !self.unsupported && self.body_end.is_none() {
            self.eof = true;
            return true;
        }
        false
    }

    pub(super) fn classification_bytes<'a>(&self, response: &'a [u8], body_allowed: bool) -> Option<&'a [u8]> {
        if !self.active() {
            return Some(response);
        }
        let headers = self.header_end?;
        let end = if body_allowed && !self.neutral() { self.body_end.unwrap_or(response.len()) } else { headers };
        response.get(..end)
    }
}
