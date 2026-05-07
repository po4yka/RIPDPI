use std::io::{self, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream};

use ripdpi_proxy_runtime_adapter::model::session::{
    encode_http_connect_reply, encode_socks4_reply, encode_socks5_reply,
};

use super::super::protocol_io::HandshakeKind;

/// Protocol-specific reply sent to the client on successful upstream connect.
pub(in crate::runtime::handshake) enum SuccessReply {
    /// Transparent proxy: no reply needed.
    None,
    /// SOCKS4: fixed success reply.
    Socks4,
    /// SOCKS5: reply includes the upstream bind address.
    Socks5,
    /// HTTP CONNECT: fixed 200 OK reply.
    HttpConnect,
}

impl SuccessReply {
    pub(super) fn handshake_kind(&self) -> Option<HandshakeKind> {
        match self {
            SuccessReply::Socks4 => Some(HandshakeKind::Socks4),
            SuccessReply::Socks5 => Some(HandshakeKind::Socks5),
            SuccessReply::HttpConnect => Some(HandshakeKind::HttpConnect),
            SuccessReply::None => None,
        }
    }

    pub(super) fn requires_client_ack(&self) -> bool {
        !matches!(self, SuccessReply::None)
    }
}

/// Write the protocol-appropriate success reply to the client.
pub(super) fn write_success_reply(
    client: &mut TcpStream,
    reply: &SuccessReply,
    upstream: Option<&TcpStream>,
) -> io::Result<()> {
    match reply {
        SuccessReply::None => Ok(()),
        SuccessReply::Socks4 => client.write_all(encode_socks4_reply(true).as_bytes()),
        SuccessReply::Socks5 => {
            let reply_addr = upstream
                .and_then(|u| u.local_addr().ok())
                .unwrap_or_else(|| SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0));
            client.write_all(encode_socks5_reply(0, reply_addr).as_bytes())
        }
        SuccessReply::HttpConnect => client.write_all(encode_http_connect_reply(true).as_bytes()),
    }
}
