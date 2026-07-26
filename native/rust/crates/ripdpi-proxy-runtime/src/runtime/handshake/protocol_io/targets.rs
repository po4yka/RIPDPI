use std::io::{self, Read};
use std::net::{SocketAddr, TcpStream};

use crate::runtime::state::RuntimeState;
use ripdpi_proxy_runtime_adapter::model::session::TargetAddr;

pub(in crate::runtime::handshake) fn read_shadowsocks_request(
    client: &mut TcpStream,
    first_byte: u8,
    state: &RuntimeState,
    mut resolver: impl FnMut(&str) -> Option<SocketAddr>,
) -> io::Result<(TargetAddr, Vec<u8>)> {
    let mut request = vec![first_byte];
    let mut chunk = [0u8; 4096];
    loop {
        if let Some((target, header_len)) = state.parse_shadowsocks_target(&request, &mut resolver) {
            return Ok((target, request[header_len..].to_vec()));
        }
        let n = client.read(&mut chunk)?;
        if n == 0 {
            return Err(io::Error::new(io::ErrorKind::UnexpectedEof, "unexpected eof during shadowsocks request"));
        }
        request.extend_from_slice(&chunk[..n]);
        if request.len() > 64 * 1024 {
            return Err(io::Error::new(io::ErrorKind::InvalidData, "shadowsocks request too large"));
        }
    }
}
