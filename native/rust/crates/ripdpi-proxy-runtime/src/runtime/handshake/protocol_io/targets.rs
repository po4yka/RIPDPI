use std::io::{self, Read};
use std::net::{SocketAddr, TcpStream};

use ripdpi_proxy_runtime_adapter::model::config::ShadowsocksTargetPolicy;
use ripdpi_proxy_runtime_adapter::model::session::SocketType;

pub(in crate::runtime::handshake) use ripdpi_proxy_runtime_adapter::model::session::parse_shadowsocks_target;

pub(in crate::runtime::handshake) fn read_shadowsocks_request(
    client: &mut TcpStream,
    first_byte: u8,
    policy: ShadowsocksTargetPolicy,
    mut resolver: impl FnMut(&str, SocketType) -> Option<SocketAddr>,
) -> io::Result<(SocketAddr, Vec<u8>)> {
    let mut request = vec![first_byte];
    let mut chunk = [0u8; 4096];
    loop {
        if let Some((target, header_len)) = parse_shadowsocks_target(&request, policy, &mut resolver) {
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
