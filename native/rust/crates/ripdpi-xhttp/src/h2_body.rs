use std::io;
use std::pin::Pin;
use std::task::{Context, Poll};

use bytes::Bytes;
use http::header::{CONTENT_TYPE, HOST, REFERER};
use http::Request;
use http_body_util::{BodyExt, Empty};
use hyper::body::{Body, Frame};
use tokio::sync::mpsc;

pub(crate) type XhttpBody = http_body_util::combinators::BoxBody<Bytes, io::Error>;

pub(crate) struct ChannelBody {
    receiver: mpsc::Receiver<io::Result<Bytes>>,
}

impl ChannelBody {
    pub(crate) fn new(receiver: mpsc::Receiver<io::Result<Bytes>>) -> Self {
        Self { receiver }
    }
}

impl Body for ChannelBody {
    type Data = Bytes;
    type Error = io::Error;

    fn poll_frame(
        mut self: Pin<&mut Self>,
        cx: &mut Context<'_>,
    ) -> Poll<Option<Result<Frame<Self::Data>, Self::Error>>> {
        match self.receiver.poll_recv(cx) {
            Poll::Ready(Some(Ok(bytes))) => Poll::Ready(Some(Ok(Frame::data(bytes)))),
            Poll::Ready(Some(Err(error))) => Poll::Ready(Some(Err(error))),
            Poll::Ready(None) => Poll::Ready(None),
            Poll::Pending => Poll::Pending,
        }
    }
}

pub(crate) fn build_get_request(
    path: &str,
    host_header: &str,
    referer: &str,
    header_padding: &str,
) -> io::Result<Request<XhttpBody>> {
    let request = Request::builder()
        .method("GET")
        .uri(path)
        .header(HOST, host_header)
        .header(REFERER, referer)
        .header("x-padding", header_padding)
        .body(Empty::<Bytes>::new().map_err(|never| match never {}).boxed())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("xHTTP GET request build: {error}")))?;
    Ok(request)
}

pub(crate) fn build_post_request(
    path: &str,
    host_header: &str,
    referer: &str,
    header_padding: &str,
    body: ChannelBody,
) -> io::Result<Request<XhttpBody>> {
    let request = Request::builder()
        .method("POST")
        .uri(path)
        .header(HOST, host_header)
        .header(REFERER, referer)
        .header("x-padding", header_padding)
        .header(CONTENT_TYPE, "application/grpc")
        .body(body.boxed())
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("xHTTP POST request build: {error}")))?;
    Ok(request)
}
