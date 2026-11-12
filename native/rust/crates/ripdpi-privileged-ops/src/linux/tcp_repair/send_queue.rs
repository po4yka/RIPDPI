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
    Ok(sequence_number.wrapping_add(payload_len))
}

#[cfg(test)]
mod tests {
    use super::sequence_after_payload;

    #[test]
    fn sequence_arithmetic_wraps_at_the_tcp_sequence_limit() {
        for (sequence, offset, expected) in [
            (0, 0, 0),
            (10, 17, 27),
            (u32::MAX, 0, u32::MAX),
            (u32::MAX, 1, 0),
            (u32::MAX - 1, 3, 1),
            (1, u32::MAX as usize, 0),
        ] {
            assert_eq!(sequence_after_payload(sequence, offset).expect("valid TCP sequence offset"), expected);
        }
    }

    #[test]
    #[cfg(target_pointer_width = "64")]
    fn sequence_arithmetic_rejects_offsets_above_u32() {
        let error = sequence_after_payload(1, u32::MAX as usize + 1).expect_err("unrepresentable offset");
        assert_eq!(error.kind(), std::io::ErrorKind::InvalidInput);
    }
}
