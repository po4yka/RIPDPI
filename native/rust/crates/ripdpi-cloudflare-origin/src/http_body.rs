use std::io;
use std::pin::Pin;
use std::task::{Context, Poll};

use bytes::Bytes;
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
