use std::io;
use std::net::{SocketAddr, TcpStream};
use std::time::Instant;

use super::readers::read_by;
use crate::runtime::state::{HANDSHAKE_TIMEOUT, RuntimeState};
use ripdpi_proxy_runtime_adapter::model::session::TargetAddr;

pub(in crate::runtime::handshake) fn read_shadowsocks_request(
    client: &mut TcpStream,
    first_byte: u8,
    state: &RuntimeState,
    resolver: impl FnMut(&str) -> Option<SocketAddr>,
    deadline: Instant,
) -> io::Result<TargetAddr> {
    let result = read_shadowsocks_request_inner(client, first_byte, state, resolver, deadline);
    let _ = client.set_read_timeout(Some(HANDSHAKE_TIMEOUT));
    result
}

fn read_shadowsocks_request_inner(
    client: &mut TcpStream,
    first_byte: u8,
    state: &RuntimeState,
    mut resolver: impl FnMut(&str) -> Option<SocketAddr>,
    deadline: Instant,
) -> io::Result<TargetAddr> {
    let mut request = vec![first_byte];
    let mut chunk = [0u8; 259];
    loop {
        if let Some((target, _header_len)) = state.parse_shadowsocks_target(&request, &mut resolver)? {
            return Ok(target);
        }
        // Leave application bytes in the socket for the common first-request assembler.
        let header_len = match first_byte {
            1 => 7,
            4 => 19,
            3 if request.len() == 1 => 2,
            3 => 4 + usize::from(request[1]),
            _ => return Err(io::Error::new(io::ErrorKind::InvalidData, "unsupported address type")),
        };
        let n = read_by(client, &mut chunk[..header_len - request.len()], deadline)?;
        if n == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "unexpected eof during shadowsocks request"));
        }
        request.extend_from_slice(&chunk[..n]);
    }
}
