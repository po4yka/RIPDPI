use std::io;
use std::task::Waker;

use smoltcp::socket::tcp::{self, Socket as TcpSocket};

use super::try_write_duplex;

pub(in crate::io_loop) fn flush_pending_to_session(
    stream: &mut tokio::io::DuplexStream,
    pending: &mut Vec<u8>,
    waker: &Waker,
) -> Option<io::Result<()>> {
    while !pending.is_empty() {
        match try_write_duplex(stream, pending, waker) {
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
