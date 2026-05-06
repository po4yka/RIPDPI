fn new_reply(error: &ReplyError, sock_addr: SocketAddr) -> Vec<u8> {
    let (addr_type, mut ip_oct, mut port) = match sock_addr {
        SocketAddr::V4(sock) => {
            (consts::SOCKS5_ADDR_TYPE_IPV4, sock.ip().octets().to_vec(), sock.port().to_be_bytes().to_vec())
        }
        SocketAddr::V6(sock) => {
            (consts::SOCKS5_ADDR_TYPE_IPV6, sock.ip().octets().to_vec(), sock.port().to_be_bytes().to_vec())
        }
    };

    let mut reply = vec![
        consts::SOCKS5_VERSION,
        error.as_u8(), // transform the error into byte code
        0x00,          // reserved
        addr_type,     // address type (ipv4, v6, domain)
    ];
    reply.append(&mut ip_oct);
    reply.append(&mut port);

    reply
}
