use std::io::{self, Write};
use std::net::{IpAddr, Ipv4Addr, SocketAddr, TcpStream};

use super::super::protocol_io::HandshakeKind;
use crate::runtime::state::RuntimeState;

/// Protocol-specific reply sent to the client on successful upstream connect.
pub(in crate::runtime::handshake) enum SuccessReply {
    /// Transparent proxy: no reply needed.
    None,
    /// Plaintext address input: no acknowledgement or WS escalation.
    PlainAddress,
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
            SuccessReply::None | SuccessReply::PlainAddress => None,
        }
    }

    pub(super) fn requires_client_ack(&self) -> bool {
        !matches!(self, SuccessReply::None | SuccessReply::PlainAddress)
    }
}

/// Write the protocol-appropriate success reply to the client.
pub(super) fn write_success_reply(
    client: &mut TcpStream,
    reply: &SuccessReply,
    upstream: Option<&TcpStream>,
) -> io::Result<()> {
    match reply {
        SuccessReply::None | SuccessReply::PlainAddress => Ok(()),
        SuccessReply::Socks4 => client.write_all(RuntimeState::encode_socks4_reply(true).as_bytes()),
        SuccessReply::Socks5 => {
            let reply_addr = upstream
                .and_then(|u| u.local_addr().ok())
                .unwrap_or_else(|| SocketAddr::new(IpAddr::V4(Ipv4Addr::LOCALHOST), 0));
            client.write_all(RuntimeState::encode_socks5_reply(0, reply_addr).as_bytes())
        }
        SuccessReply::HttpConnect => client.write_all(RuntimeState::encode_http_connect_reply(true).as_bytes()),
    }
}

#[cfg(all(test, not(feature = "loom")))]
mod tests {
    use super::*;
    use std::io::Read;
    use std::net::TcpListener;
    use std::time::Duration;

    fn pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let peer = TcpStream::connect(listener.local_addr().unwrap()).unwrap();
        (peer, listener.accept().unwrap().0)
    }

    #[test]
    fn successful_connect_reply_has_exact_protocol_bytes() {
        let (upstream, _origin) = pair();
        let port = upstream.local_addr().unwrap().port().to_be_bytes();
        let cases = [
            (SuccessReply::None, vec![]),
            (SuccessReply::Socks4, vec![0, 0x5a, 0, 0, 0, 0, 0, 0]),
            (SuccessReply::Socks5, vec![5, 0, 0, 1, 127, 0, 0, 1, port[0], port[1]]),
            (SuccessReply::HttpConnect, b"HTTP/1.1 200 OK\r\n\r\n".to_vec()),
        ];
        for (reply, expected) in cases {
            let (mut writer, mut reader) = pair();
            reader.set_read_timeout(Some(Duration::from_secs(1))).unwrap();
            write_success_reply(&mut writer, &reply, Some(&upstream)).unwrap();
            drop(writer);
            let mut actual = Vec::new();
            reader.read_to_end(&mut actual).unwrap();
            assert_eq!(actual, expected);
        }
    }
}
