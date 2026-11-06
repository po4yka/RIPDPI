use std::collections::HashMap;
use std::future::Future;
use std::io;
use std::pin::Pin;
use std::sync::{
    Arc,
    atomic::{AtomicBool, Ordering},
};
use std::task::{Context, Poll, Wake, Waker};

use bytes::Bytes;
use futures::channel::mpsc;
use futures::{SinkExt, Stream};
use http::{HeaderMap, Method};
use http_body_util::{BodyExt, Empty};
use hyper::rt::{Read, ReadBufCursor, Write};

use super::HttpResponse;

const INPUT_CAPACITY: usize = 8192;
pub(super) const MAX_INFORMATIONAL: usize = 4;

/// The source of the response body's completion boundary.
#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum HttpBodyFraming {
    Length(u64),
    Chunked,
    Eof,
}

/// A library-decoded response event. Input is requested only between polls.
#[derive(Debug)]
pub enum HttpResponseEvent {
    NeedInput,
    Head(HttpResponse, HttpBodyFraming),
    Data(Bytes),
    Complete,
}

/// Response-only HTTP/1 decoder for already-sent diagnostic requests.
///
/// The Hyper request supplies method context only. Its output goes to a sink,
/// never to the network, so probes retain their exact request bytes. Drop ends
/// decoding immediately; this type creates no tasks, runtimes, or threads.
pub struct HttpResponseDecoder {
    input: mpsc::Sender<Bytes>,
    can_feed: bool,
    events: mpsc::Receiver<HttpResponseEvent>,
    driver: Pin<Box<dyn Future<Output = Result<(), &'static str>>>>,
    result: Option<Result<(), &'static str>>,
    head_received: bool,
    head_budget: usize,
    pending_head: Vec<u8>,
    method: Method,
    informational: usize,
    head_validated: bool,
    wake: Arc<PollWake>,
    waker: Waker,
}

impl HttpResponseDecoder {
    pub fn new(method: &str, max_header_bytes: usize) -> Result<Self, &'static str> {
        let method = Method::from_bytes(method.as_bytes()).map_err(|_| "invalid_framing")?;
        let (input, incoming) = mpsc::channel(0);
        let wake = Arc::new(PollWake::default());
        let io = BufferedInput { incoming, bytes: Bytes::new(), wake: wake.clone() };
        let (events_tx, events) = mpsc::channel(0);
        let driver = Box::pin(decode(io, method.clone(), max_header_bytes, events_tx));
        let waker = Waker::from(wake.clone());
        Ok(Self {
            input,
            can_feed: true,
            events,
            driver,
            result: None,
            head_received: false,
            head_budget: max_header_bytes,
            pending_head: Vec::new(),
            method,
            informational: 0,
            head_validated: false,
            wake,
            waker,
        })
    }

    /// Maximum bytes that the next feed can accept without unbounded buffering.
    pub fn input_capacity(&self) -> usize {
        if self.head_received { INPUT_CAPACITY } else { INPUT_CAPACITY.min(self.head_budget) }
    }

    pub fn feed(&mut self, bytes: &[u8]) -> Result<(), &'static str> {
        if !self.can_feed || bytes.len() > self.input_capacity() {
            return Err("response_too_large");
        }
        if bytes.is_empty() {
            return Ok(());
        }
        if !self.head_validated {
            self.head_budget -= bytes.len();
            self.pending_head.extend_from_slice(bytes);
            loop {
                let mut fields = [httparse::EMPTY_HEADER; 128];
                let mut parsed = httparse::Response::new(&mut fields);
                let httparse::Status::Complete(end) =
                    parsed.parse(&self.pending_head).map_err(|_| "invalid_framing")?
                else {
                    break;
                };
                let (headers, _, response) =
                    super::response_parser::parsed_head(&self.pending_head[..end]).map_err(|_| "invalid_framing")?;
                response_framing(&headers, response.status_code, &self.method)?;
                if (100..200).contains(&response.status_code) {
                    self.informational += 1;
                    if response.status_code == 101 || self.informational > MAX_INFORMATIONAL {
                        return Err("invalid_framing");
                    }
                    self.pending_head.drain(..end);
                } else {
                    self.head_budget += self.pending_head.len() - end;
                    self.head_validated = true;
                    self.pending_head.clear();
                    break;
                }
            }
        }
        self.input.try_send(Bytes::copy_from_slice(bytes)).map_err(|_| "decoder_closed")?;
        self.can_feed = false;
        Ok(())
    }

    pub fn finish_input(&mut self) {
        self.input.close_channel();
        self.can_feed = false;
    }

    pub fn next_event(&mut self) -> Result<HttpResponseEvent, &'static str> {
        let waker = self.waker.clone();
        let mut context = Context::from_waker(&waker);
        loop {
            if let Poll::Ready(Some(event)) = Pin::new(&mut self.events).poll_next(&mut context) {
                self.head_received |= matches!(event, HttpResponseEvent::Head(..));
                return Ok(event);
            }
            if let Some(result) = self.result {
                return result.map(|()| HttpResponseEvent::Complete);
            }
            self.wake.needs_input.store(false, Ordering::Relaxed);
            self.wake.notified.store(false, Ordering::Relaxed);
            if let Poll::Ready(result) = self.driver.as_mut().poll(&mut context) {
                self.result = Some(result);
                continue;
            }
            if let Poll::Ready(Some(event)) = Pin::new(&mut self.events).poll_next(&mut context) {
                self.head_received |= matches!(event, HttpResponseEvent::Head(..));
                return Ok(event);
            }
            // All polling occurs on this thread. The flag only records a request
            // to poll again; it publishes no data, so Relaxed is sufficient.
            if self.wake.notified.swap(false, Ordering::Relaxed) {
                continue;
            }
            if self.wake.needs_input.load(Ordering::Relaxed) {
                if self.input_capacity() == 0 {
                    return Err("response_too_large");
                }
                self.can_feed = true;
                return Ok(HttpResponseEvent::NeedInput);
            }
        }
    }
}

#[derive(Default)]
struct PollWake {
    notified: AtomicBool,
    needs_input: AtomicBool,
}
impl Wake for PollWake {
    fn wake(self: Arc<Self>) {
        self.wake_by_ref();
    }
    fn wake_by_ref(self: &Arc<Self>) {
        self.notified.store(true, Ordering::Relaxed);
    }
}

struct BufferedInput {
    incoming: mpsc::Receiver<Bytes>,
    bytes: Bytes,
    wake: Arc<PollWake>,
}

impl Read for BufferedInput {
    fn poll_read(
        mut self: Pin<&mut Self>,
        context: &mut Context<'_>,
        mut buffer: ReadBufCursor<'_>,
    ) -> Poll<io::Result<()>> {
        if buffer.remaining() == 0 {
            return Poll::Ready(Ok(()));
        }
        if self.bytes.is_empty() {
            match Pin::new(&mut self.incoming).poll_next(context) {
                Poll::Ready(Some(bytes)) => self.bytes = bytes,
                Poll::Ready(None) => return Poll::Ready(Ok(())),
                Poll::Pending => {
                    // Same-thread control signal only; payload ownership passes through the channel.
                    self.wake.needs_input.store(true, Ordering::Relaxed);
                    return Poll::Pending;
                }
            }
        }
        let count = self.bytes.len().min(buffer.remaining());
        buffer.put_slice(&self.bytes.split_to(count));
        Poll::Ready(Ok(()))
    }
}

impl Write for BufferedInput {
    fn poll_write(self: Pin<&mut Self>, _: &mut Context<'_>, bytes: &[u8]) -> Poll<io::Result<usize>> {
        Poll::Ready(Ok(bytes.len()))
    }

    fn poll_flush(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }

    fn poll_shutdown(self: Pin<&mut Self>, _: &mut Context<'_>) -> Poll<io::Result<()>> {
        Poll::Ready(Ok(()))
    }
}

/// # Cancel safety:
/// cancel-safe: dropping the decoder drops the buffered IO and connection driver.
async fn decode(
    io: BufferedInput,
    method: Method,
    max_header_bytes: usize,
    mut events: mpsc::Sender<HttpResponseEvent>,
) -> Result<(), &'static str> {
    let (mut sender, connection) = hyper::client::conn::http1::Builder::new()
        .max_buf_size(max_header_bytes.max(INPUT_CAPACITY))
        .max_headers(128)
        .handshake(io)
        .await
        .map_err(decode_error)?;
    let mut request = http::Request::new(Empty::<Bytes>::new());
    *request.method_mut() = method.clone();
    let exchange = async move {
        let response = sender.send_request(request).await.map_err(decode_error)?;
        let framing = response_framing(response.headers(), response.status().as_u16(), &method)?;
        let headers = response.headers().iter().fold(HashMap::<String, String>::new(), |mut headers, (name, value)| {
            let value = String::from_utf8_lossy(value.as_bytes());
            headers
                .entry(name.to_string())
                .and_modify(|old| {
                    old.push_str(", ");
                    old.push_str(&value);
                })
                .or_insert_with(|| value.into_owned());
            headers
        });
        let head = HttpResponse {
            status_code: response.status().as_u16(),
            reason: response.extensions().get::<hyper::ext::ReasonPhrase>().map_or_else(
                || response.status().canonical_reason().unwrap_or_default().to_string(),
                |value| String::from_utf8_lossy(value.as_bytes()).into_owned(),
            ),
            headers,
            body: Vec::new(),
        };
        events.send(HttpResponseEvent::Head(head, framing)).await.map_err(|_| "decoder_closed")?;
        if framing == HttpBodyFraming::Length(0) {
            return Ok(());
        }
        let mut body = response.into_body();
        while let Some(frame) = body.frame().await {
            let frame = frame.map_err(decode_error)?;
            if let Ok(data) = frame.into_data() {
                events.send(HttpResponseEvent::Data(data)).await.map_err(|_| "decoder_closed")?;
            }
        }
        Ok(())
    };
    futures::pin_mut!(exchange, connection);
    match futures::future::select(exchange, connection).await {
        futures::future::Either::Left((result, _)) => result,
        futures::future::Either::Right((_, exchange)) => exchange.await,
    }
}

pub(super) fn response_framing(
    headers: &HeaderMap,
    status: u16,
    method: &Method,
) -> Result<HttpBodyFraming, &'static str> {
    let mut length = None;
    for value in headers.get_all(http::header::CONTENT_LENGTH) {
        for part in value.to_str().map_err(|_| "invalid_framing")?.split(',') {
            let part = part.trim();
            if part.is_empty() || !part.bytes().all(|byte| byte.is_ascii_digit()) {
                return Err("invalid_framing");
            }
            let parsed = part.parse::<u64>().map_err(|_| "invalid_framing")?;
            if parsed > i64::MAX as u64 || length.is_some_and(|old| old != parsed) {
                return Err("invalid_framing");
            }
            length = Some(parsed);
        }
    }
    let mut transfer = headers.get_all(http::header::TRANSFER_ENCODING).iter();
    if let Some(value) = transfer.next() {
        if length.is_some() || transfer.next().is_some() || !value.as_bytes().eq_ignore_ascii_case(b"chunked") {
            return Err("invalid_framing");
        }
        return Ok(if method == Method::HEAD || matches!(status, 204 | 205 | 304) {
            HttpBodyFraming::Length(0)
        } else {
            HttpBodyFraming::Chunked
        });
    }
    if method == Method::HEAD || matches!(status, 204 | 205 | 304) {
        return Ok(HttpBodyFraming::Length(0));
    }
    Ok(length.map_or(HttpBodyFraming::Eof, HttpBodyFraming::Length))
}

fn decode_error(error: hyper::Error) -> &'static str {
    use std::error::Error;
    let mut source = error.source();
    while let Some(cause) = source {
        if cause.downcast_ref::<io::Error>().is_some_and(|error| error.kind() == io::ErrorKind::UnexpectedEof) {
            return "response_truncated";
        }
        source = cause.source();
    }
    if error.is_incomplete_message() { "response_truncated" } else { "invalid_framing" }
}
