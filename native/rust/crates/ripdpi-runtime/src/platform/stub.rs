use std::io;
use std::net::{SocketAddr, TcpStream};

pub fn enable_tcp_fastopen_connect() -> io::Result<()> {
    Err(io::Error::new(
        io::ErrorKind::Unsupported,
        "tcp fast open connect is linux-only",
    ))
}

pub fn set_tcp_md5sig(_stream: &TcpStream, _key_len: u16) -> io::Result<()> {
    Ok(())
}

pub fn protect_socket<T>(_socket: &T, _path: &str) -> io::Result<()> {
    Ok(())
}

pub fn original_dst(_stream: &TcpStream) -> io::Result<SocketAddr> {
    Err(io::Error::new(
        io::ErrorKind::Unsupported,
        "transparent proxy mode is linux-only",
    ))
}

pub fn attach_drop_sack(_stream: &TcpStream) -> io::Result<()> {
    Ok(())
}

pub fn detach_drop_sack(_stream: &TcpStream) -> io::Result<()> {
    Ok(())
}

pub fn send_fake_tcp(
    stream: &TcpStream,
    fake_prefix: &[u8],
    ttl: u8,
    md5sig: bool,
    default_ttl: u8,
) -> io::Result<()> {
    super::fallback::send_fake_tcp_best_effort(
        stream,
        fake_prefix,
        ttl,
        md5sig,
        default_ttl,
        set_tcp_md5sig,
    )
}

pub fn wait_tcp_stage(
    _stream: &TcpStream,
    _wait_send: bool,
    _await_interval: std::time::Duration,
) -> io::Result<()> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::time::Duration;

    fn connected_pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind(("127.0.0.1", 0)).expect("bind listener");
        let addr = listener.local_addr().expect("listener addr");
        let client = TcpStream::connect(addr).expect("connect client");
        let (server, _) = listener.accept().expect("accept client");
        (client, server)
    }

    #[test]
    fn unsupported_operations_report_unsupported() {
        assert_eq!(
            enable_tcp_fastopen_connect()
                .expect_err("tfo must be unsupported")
                .kind(),
            io::ErrorKind::Unsupported
        );

        let (client, _server) = connected_pair();
        assert_eq!(
            original_dst(&client)
                .expect_err("original dst must be unsupported")
                .kind(),
            io::ErrorKind::Unsupported
        );
    }

    #[test]
    fn send_fake_tcp_writes_fake_prefix() {
        let (client, mut server) = connected_pair();
        server
            .set_read_timeout(Some(Duration::from_secs(1)))
            .expect("set read timeout");

        send_fake_tcp(&client, b"fake-payload", 0, false, 0).expect("send fake payload");

        let mut buf = [0u8; 12];
        server.read_exact(&mut buf).expect("read fake payload");
        assert_eq!(&buf, b"fake-payload");
    }

    #[test]
    fn no_op_helpers_succeed() {
        let (client, _server) = connected_pair();
        protect_socket(&client, "/tmp/ignored").expect("protect socket no-op");
        attach_drop_sack(&client).expect("attach drop sack no-op");
        detach_drop_sack(&client).expect("detach drop sack no-op");
        wait_tcp_stage(&client, true, Duration::from_millis(1)).expect("wait tcp stage no-op");
        set_tcp_md5sig(&client, 5).expect("md5sig no-op");

        let mut writer = client.try_clone().expect("clone client");
        writer.write_all(b"x").expect("socket remains writable");
    }
}
