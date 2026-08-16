use std::io;
use std::net::TcpStream;

use socket2::SockRef;

use crate::types::OutboundSendError;

pub(crate) fn send_out_of_band(writer: &TcpStream, prefix: &[u8], urgent_byte: u8) -> io::Result<()> {
    let mut packet = Vec::with_capacity(prefix.len() + 1);
    packet.extend_from_slice(prefix);
    packet.push(urgent_byte);
    let sent = SockRef::from(writer).send_out_of_band(&packet)?;
    if sent != packet.len() {
        return Err(io::Error::new(io::ErrorKind::WriteZero, "partial MSG_OOB send"));
    }
    Ok(())
}

pub(crate) fn set_stream_ttl(stream: &TcpStream, ttl: u8) -> io::Result<()> {
    let socket = SockRef::from(stream);
    let ipv4 = socket.set_ttl_v4(ttl as u32);
    let ipv6 = socket.set_unicast_hops_v6(ttl as u32);
    match (ipv4, ipv6) {
        (Ok(()), _) | (_, Ok(())) => Ok(()),
        (Err(err), _) => Err(err),
    }
}

pub(crate) fn send_transport_oob_payload(
    writer: &TcpStream,
    prefix: &[u8],
    urgent_byte: u8,
) -> Result<usize, OutboundSendError> {
    send_out_of_band(writer, prefix, urgent_byte).map(|()| prefix.len() + 1).map_err(OutboundSendError::transport)
}
