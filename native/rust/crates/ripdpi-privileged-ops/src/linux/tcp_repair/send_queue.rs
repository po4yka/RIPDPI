use std::io::{self, Write};
use std::net::TcpStream;
use std::os::fd::AsRawFd;

use crate::linux::tcp_info::tcp_has_notsent;

use super::sockopt::{TCP_NO_QUEUE, TCP_SEND_QUEUE, set_tcp_repair_queue};

/// Puts `payload` in the send queue of `stream`, which must be in
/// TCP_REPAIR, as data that is already sent.
///
/// The kernel moves the send sequence past `payload` and keeps the bytes for
/// retransmission, but transmits nothing now: the caller has sent them in a
/// crafted raw packet. A second socket on the same 4-tuple cannot do this,
/// because its connect() fails with EADDRNOTAVAIL while the original socket
/// is still hashed.
pub(crate) fn queue_payload_as_sent(stream: &TcpStream, payload: &[u8]) -> io::Result<()> {
    let fd = stream.as_raw_fd();
    set_tcp_repair_queue(fd, TCP_SEND_QUEUE)?;
    (&*stream).write_all(payload)?;
    if tcp_has_notsent(fd)? {
        // Autocorking can hold the bytes back. Setting TCP_NODELAY pushes
        // them through the repair path, which marks them sent.
        let nodelay = stream.nodelay()?;
        stream.set_nodelay(true)?;
        stream.set_nodelay(nodelay)?;
    }
    set_tcp_repair_queue(fd, TCP_NO_QUEUE)
}

pub(crate) fn sequence_after_payload(sequence_number: u32, payload_len: usize) -> io::Result<u32> {
    let payload_len = u32::try_from(payload_len)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidInput, "payload too large for TCP sequence arithmetic"))?;
    sequence_number
        .checked_add(payload_len)
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "TCP sequence arithmetic overflow"))
}
