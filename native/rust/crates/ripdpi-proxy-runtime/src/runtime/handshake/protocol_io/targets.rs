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
) -> io::Result<(TargetAddr, Vec<u8>)> {
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
) -> io::Result<(TargetAddr, Vec<u8>)> {
    let mut request = vec![first_byte];
    let mut chunk = [0u8; 4096];
    loop {
        if let Some((target, header_len)) = state.parse_shadowsocks_target(&request, &mut resolver) {
            return Ok((target, request[header_len..].to_vec()));
        }
        let n = read_by(client, &mut chunk, deadline)?;
        if n == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "unexpected eof during shadowsocks request"));
        }
        request.extend_from_slice(&chunk[..n]);
        if request.len() > 64 * 1024 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "shadowsocks request too large"));
        }
    }
}
