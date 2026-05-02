use std::io::{self, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream};

use ripdpi_session::{encode_http_connect_reply, encode_socks4_reply, encode_socks5_reply};

#[derive(Clone, Copy)]
pub(in crate::runtime::handshake) enum HandshakeKind {
    Socks4,
    Socks5,
    HttpConnect,
}

pub(in crate::runtime::handshake) fn send_success_reply(
    client: &mut TcpStream,
    handshake: HandshakeKind,
) -> io::Result<()> {
    match handshake {
        HandshakeKind::Socks4 => client.write_all(encode_socks4_reply(true).as_bytes()),
        HandshakeKind::Socks5 => {
            let addr = SocketAddr::new(IpAddr::V4(Ipv4Addr::UNSPECIFIED), 0);
            client.write_all(encode_socks5_reply(0, addr).as_bytes())
        }
        HandshakeKind::HttpConnect => client.write_all(encode_http_connect_reply(true).as_bytes()),
    }
}
