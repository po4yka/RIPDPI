use std::io;
use std::task::Waker;

use smoltcp::socket::tcp::Socket as TcpSocket;

use crate::SessionEntry;
use crate::io_loop::PUMP_CHUNK;

use super::super::duplex::{flush_pending_to_session, shutdown_duplex_write, try_write_duplex};

pub(super) fn pump_from_app(tcp: &mut TcpSocket, session: &mut SessionEntry, waker: &Waker) -> io::Result<()> {
    if let Some(Err(error)) =
        flush_pending_to_session(&mut session.smoltcp_side, &mut session.pending_to_session, waker)
    {
        return Err(error);
    }
    if session.pending_to_session.is_empty() {
        let mut buffer = [0; PUMP_CHUNK];
        if let Ok(read) = tcp.recv_slice(&mut buffer)
            && read > 0
        {
            match try_write_duplex(&mut session.smoltcp_side, &buffer[..read], waker) {
                Some(Ok(0)) => return Err(io::ErrorKind::WriteZero.into()),
                Some(Ok(sent)) => session.pending_to_session.extend_from_slice(&buffer[sent..read]),
                Some(Err(error)) => return Err(error),
                None => session.pending_to_session.extend_from_slice(&buffer[..read]),
            }
        }
    }
    if session.pending_to_session.is_empty() {
        if !tcp.may_recv() {
            shutdown_duplex_write(&mut session.smoltcp_side);
        } else if tcp.can_recv() {
            // TIME-WAIT can have buffered data but no short poll timer left to drain it.
            waker.wake_by_ref();
        }
    }
    Ok(())
}
