use crate::util;

#[derive(Debug)]
pub(crate) struct HttpRequest {
    pub(crate) method: String,
    pub(crate) path: String,
    pub(crate) query: String,
    pub(crate) body: Vec<u8>,
    pub(crate) raw: Vec<u8>,
}

impl HttpRequest {
    pub(crate) fn query_param(&self, key: &str) -> Option<String> {
        self.query.split('&').find_map(|entry| {
            let (name, value) = entry.split_once('=')?;
            (name == key).then(|| util::percent_decode(value))
        })
    }
}

#[derive(Debug)]
pub(crate) struct HttpResponse {
    status_line: &'static str,
    content_type: &'static str,
    body: Vec<u8>,
}

impl HttpResponse {
    fn ok(content_type: &'static str, body: Vec<u8>) -> Self {
        Self { status_line: "HTTP/1.1 200 OK", content_type, body }
    }

    pub(crate) fn json(body: String) -> Self {
        Self::ok("application/json", body.into_bytes())
    }

    pub(crate) fn dns_message(body: Vec<u8>) -> Self {
        Self::ok("application/dns-message", body)
    }

    pub(crate) fn odoh_message(body: Vec<u8>) -> Self {
        Self::ok("application/oblivious-dns-message", body)
    }

    pub(crate) fn text(body: &str) -> Self {
        Self {
            status_line: "HTTP/1.1 200 OK",
            content_type: "text/plain; charset=utf-8",
            body: body.as_bytes().to_vec(),
        }
    }

    pub(crate) fn not_found() -> Self {
        Self {
            status_line: "HTTP/1.1 404 Not Found",
            content_type: "text/plain; charset=utf-8",
            body: b"not found".to_vec(),
        }
    }

    pub(crate) fn bad_request(body: &str) -> Self {
        Self {
            status_line: "HTTP/1.1 400 Bad Request",
            content_type: "text/plain; charset=utf-8",
            body: body.as_bytes().to_vec(),
        }
    }

    pub(crate) fn to_bytes(&self) -> Vec<u8> {
        let headers = format!(
            "{}\r\nContent-Type: {}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
            self.status_line,
            self.content_type,
            self.body.len()
        );
        let mut bytes = headers.into_bytes();
        bytes.extend_from_slice(&self.body);
        bytes
    }
}
