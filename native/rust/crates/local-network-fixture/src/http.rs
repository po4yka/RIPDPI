use std::io::{self, ErrorKind, Read, Write};
use std::net::{Shutdown, SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::thread::{self, JoinHandle};

use crate::event::{event, EventLog};
use crate::types::IO_POLL_DELAY;
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
    pub(crate) fn json(body: String) -> Self {
        Self { status_line: "HTTP/1.1 200 OK", content_type: "application/json", body: body.into_bytes() }
    }

    pub(crate) fn dns_message(body: Vec<u8>) -> Self {
        Self { status_line: "HTTP/1.1 200 OK", content_type: "application/dns-message", body }
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

pub(crate) fn start_http_server<F>(
    bind_host: String,
    port: u16,
    stop: Arc<AtomicBool>,
    events: EventLog,
    handler: F,
) -> io::Result<(JoinHandle<()>, u16)>
where
    F: Fn(HttpRequest, SocketAddr, Option<SocketAddr>) -> HttpResponse + Send + Sync + 'static,
{
    let listener = TcpListener::bind((bind_host.as_str(), port))?;
    listener.set_nonblocking(true)?;
    let local_port = listener.local_addr()?.port();
    let handler = Arc::new(handler);
    Ok((
        thread::spawn(move || {
            while !stop.load(Ordering::Relaxed) {
                match listener.accept() {
                    Ok((mut stream, peer)) => {
                        let handler = handler.clone();
                        let events = events.clone();
                        thread::spawn(move || {
                            let local = stream.local_addr().ok();
                            let request = parse_http_request(&mut stream);
                            let response = match request {
                                Ok(request) => handler(request, peer, local),
                                Err(err) => {
                                    events.record(event("http_error", "http", peer, local, &err.to_string(), 0, None));
                                    HttpResponse::not_found()
                                }
                            };
                            let _ = stream.write_all(&response.to_bytes());
                            let _ = stream.flush();
                            let _ = stream.shutdown(Shutdown::Both);
                        });
                    }
                    Err(err) if err.kind() == ErrorKind::WouldBlock => thread::sleep(IO_POLL_DELAY),
                    Err(_) => break,
                }
            }
        }),
        local_port,
    ))
}

fn parse_http_request(stream: &mut TcpStream) -> io::Result<HttpRequest> {
    let mut raw = read_until_marker(stream, b"\r\n\r\n");
    let request = String::from_utf8_lossy(&raw).into_owned();
    let first_line = request.lines().next().ok_or_else(|| io::Error::new(ErrorKind::InvalidData, "empty request"))?;
    let mut parts = first_line.split_whitespace();
    let method = parts.next().unwrap_or("GET").to_string();
    let target = parts.next().unwrap_or("/");
    let (path, query) = normalize_http_target(target);
    let content_length = request
        .lines()
        .find_map(|line| {
            let (name, value) = line.split_once(':')?;
            name.eq_ignore_ascii_case("content-length").then(|| value.trim().parse::<usize>().ok()).flatten()
        })
        .unwrap_or(0);
    let mut body = vec![0u8; content_length];
    if content_length > 0 {
        stream.read_exact(&mut body)?;
        raw.extend_from_slice(&body);
    }
    Ok(HttpRequest { method, path: path.to_string(), query: query.to_string(), body, raw })
}

fn normalize_http_target(target: &str) -> (&str, &str) {
    let normalized = if let Some((_, rest)) = target.split_once("://") {
        let slash = rest.find('/').map_or(rest.len(), |offset| offset + 1);
        let suffix = &rest[slash..];
        if suffix.is_empty() {
            "/"
        } else {
            suffix
        }
    } else {
        target
    };
    normalized.split_once('?').unwrap_or((normalized, ""))
}

pub(crate) fn read_until_marker(stream: &mut impl Read, marker: &[u8]) -> Vec<u8> {
    let mut buf = Vec::new();
    let mut chunk = [0u8; 1];
    while !buf.windows(marker.len()).any(|window| window == marker) {
        match stream.read(&mut chunk) {
            Ok(0) => break,
            Ok(read) => buf.extend_from_slice(&chunk[..read]),
            Err(_) => break,
        }
    }
    buf
}
