#[derive(Clone, Copy)]
pub(in crate::runtime::handshake) enum HandshakeKind {
    Socks4,
    Socks5,
    HttpConnect,
}
