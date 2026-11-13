use std::io;
use std::net::TcpStream;

use socket2::SockRef;

use crate::types::OutboundSendError;

use super::progress::{WriteProgressError, write_payload_progress};

/// Writes `prefix` as ordinary data, then sends only `urgent_byte` with MSG_OOB.
///
/// One MSG_OOB send of the whole buffer can be split under send-buffer pressure. Linux then
/// marks the last byte of each pushed part urgent, which removes source bytes from the peer stream.
/// On error, `written` counts accepted prefix bytes; `written == prefix.len()` means the urgent
/// send was attempted and its result is unknown to the peer.
pub(crate) fn send_out_of_band(writer: &TcpStream, prefix: &[u8], urgent_byte: u8) -> Result<(), WriteProgressError> {
    write_payload_progress(writer, prefix)?;
    loop {
        let source = match SockRef::from(writer).send_out_of_band(&[urgent_byte]) {
            Ok(1) => return Ok(()),
            Ok(_) => io::Error::new(io::ErrorKind::WriteZero, "MSG_OOB send accepted no byte"),
            Err(err) if err.kind() == io::ErrorKind::Interrupted => continue,
            Err(err) => err,
        };
        return Err(WriteProgressError { written: prefix.len(), source });
    }
}

pub(crate) fn get_stream_ttl(stream: &TcpStream) -> io::Result<u8> {
    let socket = SockRef::from(stream);
    let ttl = if stream_uses_ipv4_ttl(stream)? { socket.ttl_v4()? } else { socket.unicast_hops_v6()? };
    u8::try_from(ttl).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "socket TTL exceeds u8"))
}

pub(crate) fn set_stream_ttl(stream: &TcpStream, ttl: u8) -> io::Result<()> {
    let socket = SockRef::from(stream);
    if stream_uses_ipv4_ttl(stream)? { socket.set_ttl_v4(ttl as u32) } else { socket.set_unicast_hops_v6(ttl as u32) }
}

fn stream_uses_ipv4_ttl(stream: &TcpStream) -> io::Result<bool> {
    let peer = stream.peer_addr()?.ip();
    // Linux uses IPv4 TTL for IPv4-mapped IPv6 peers.
    #[cfg(any(target_os = "linux", target_os = "android"))]
    let peer = peer.to_canonical();
    Ok(peer.is_ipv4())
}

pub(crate) fn send_transport_oob_payload(
    writer: &TcpStream,
    prefix: &[u8],
    urgent_byte: u8,
) -> Result<usize, OutboundSendError> {
    // The inserted urgent byte is not application data, so it is not counted.
    send_out_of_band(writer, prefix, urgent_byte)
        .map(|()| prefix.len())
        .map_err(|progress| OutboundSendError::transport_with_progress(progress.source, progress.written))
}
