use std::io;
use std::pin::Pin;
use std::task::{Context, Poll, Waker};

use smoltcp::socket::tcp::{self, Socket as TcpSocket};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

pub(in crate::io_loop) fn try_read_duplex(
    stream: &mut tokio::io::DuplexStream,
    buf: &mut [u8],
) -> Option<io::Result<usize>> {
    let waker = Waker::noop();
    let mut cx = Context::from_waker(waker);
    let mut rb = ReadBuf::new(buf);
    match Pin::new(stream).poll_read(&mut cx, &mut rb) {
        Poll::Ready(Ok(())) => Some(Ok(rb.filled().len())),
        Poll::Ready(Err(e)) => Some(Err(e)),
        Poll::Pending => None,
    }
}

pub(in crate::io_loop) fn try_write_duplex(
    stream: &mut tokio::io::DuplexStream,
    buf: &[u8],
) -> Option<io::Result<usize>> {
    let waker = Waker::noop();
    let mut cx = Context::from_waker(waker);
    match Pin::new(stream).poll_write(&mut cx, buf) {
        Poll::Ready(Ok(n)) => Some(Ok(n)),
        Poll::Ready(Err(e)) => Some(Err(e)),
        Poll::Pending => None,
    }
}

pub(in crate::io_loop) fn flush_pending_to_session(
    stream: &mut tokio::io::DuplexStream,
    pending: &mut Vec<u8>,
) -> Option<io::Result<()>> {
    while !pending.is_empty() {
        match try_write_duplex(stream, pending) {
            Some(Ok(0)) => {
                return Some(Err(io::Error::new(
                    io::ErrorKind::WriteZero,
                    "session duplex stream accepted zero bytes",
                )));
            }
            Some(Ok(sent)) => {
                pending.drain(..sent);
            }
            Some(Err(e)) => return Some(Err(e)),
            None => return None,
        }
    }
    Some(Ok(()))
}

pub(in crate::io_loop) fn flush_pending_to_smoltcp(
    tcp: &mut TcpSocket,
    pending: &mut Vec<u8>,
) -> Result<(), tcp::SendError> {
    while !pending.is_empty() {
        let sent = tcp.send_slice(pending)?;
        if sent == 0 {
            break;
        }
        pending.drain(..sent);
    }
    Ok(())
}
