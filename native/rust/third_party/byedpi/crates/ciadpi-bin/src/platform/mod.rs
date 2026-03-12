use std::io;
use std::net::{SocketAddr, TcpStream};
use std::time::Duration;

use ciadpi_desync::TcpSegmentHint;
use socket2::{Domain, Protocol, Socket, Type};

#[cfg(target_os = "linux")]
mod linux;

#[cfg(not(target_os = "linux"))]
mod fallback;

#[cfg(not(any(target_os = "linux", target_os = "windows")))]
mod stub;

#[cfg(target_os = "windows")]
pub mod windows;

pub type TcpStageWait = (bool, Duration);

pub fn detect_default_ttl() -> io::Result<u8> {
    let socket = Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP))?;
    let ttl = socket.ttl()?;
    u8::try_from(ttl)
        .map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "socket ttl exceeds u8"))
}

#[cfg(target_os = "linux")]
pub fn enable_tcp_fastopen_connect<T: std::os::fd::AsRawFd>(socket: &T) -> io::Result<()> {
    linux::enable_tcp_fastopen_connect(socket)
}

#[cfg(not(target_os = "linux"))]
pub fn enable_tcp_fastopen_connect<T>(_socket: &T) -> io::Result<()> {
    #[cfg(target_os = "windows")]
    {
        return windows::enable_tcp_fastopen_connect();
    }
    #[cfg(not(target_os = "windows"))]
    stub::enable_tcp_fastopen_connect()
}

#[cfg(target_os = "linux")]
pub fn set_tcp_md5sig(stream: &TcpStream, key_len: u16) -> io::Result<()> {
    linux::set_tcp_md5sig(stream, key_len)
}

#[cfg(not(target_os = "linux"))]
pub fn set_tcp_md5sig(stream: &TcpStream, key_len: u16) -> io::Result<()> {
    #[cfg(target_os = "windows")]
    {
        return windows::set_tcp_md5sig(stream, key_len);
    }
    #[cfg(not(target_os = "windows"))]
    stub::set_tcp_md5sig(stream, key_len)
}

#[cfg(target_os = "linux")]
pub fn protect_socket<T: std::os::fd::AsRawFd>(socket: &T, path: &str) -> io::Result<()> {
    linux::protect_socket(socket, path)
}

#[cfg(not(target_os = "linux"))]
pub fn protect_socket<T>(socket: &T, path: &str) -> io::Result<()> {
    #[cfg(target_os = "windows")]
    {
        return windows::protect_socket(socket, path);
    }
    #[cfg(not(target_os = "windows"))]
    stub::protect_socket(socket, path)
}

#[cfg(target_os = "linux")]
pub fn original_dst(stream: &TcpStream) -> io::Result<SocketAddr> {
    linux::original_dst(stream)
}

#[cfg(not(target_os = "linux"))]
pub fn original_dst(stream: &TcpStream) -> io::Result<SocketAddr> {
    #[cfg(target_os = "windows")]
    {
        return windows::original_dst(stream);
    }
    #[cfg(not(target_os = "windows"))]
    stub::original_dst(stream)
}

#[cfg(target_os = "linux")]
pub fn attach_drop_sack(stream: &TcpStream) -> io::Result<()> {
    linux::attach_drop_sack(stream)
}

#[cfg(not(target_os = "linux"))]
pub fn attach_drop_sack(stream: &TcpStream) -> io::Result<()> {
    #[cfg(target_os = "windows")]
    {
        return windows::attach_drop_sack(stream);
    }
    #[cfg(not(target_os = "windows"))]
    stub::attach_drop_sack(stream)
}

#[cfg(target_os = "linux")]
pub fn detach_drop_sack(stream: &TcpStream) -> io::Result<()> {
    linux::detach_drop_sack(stream)
}

#[cfg(not(target_os = "linux"))]
pub fn detach_drop_sack(stream: &TcpStream) -> io::Result<()> {
    #[cfg(target_os = "windows")]
    {
        return windows::detach_drop_sack(stream);
    }
    #[cfg(not(target_os = "windows"))]
    stub::detach_drop_sack(stream)
}

#[cfg(target_os = "linux")]
pub fn send_fake_tcp(
    stream: &TcpStream,
    original_prefix: &[u8],
    fake_prefix: &[u8],
    ttl: u8,
    md5sig: bool,
    default_ttl: u8,
    wait: TcpStageWait,
) -> io::Result<()> {
    linux::send_fake_tcp(
        stream,
        original_prefix,
        fake_prefix,
        ttl,
        md5sig,
        default_ttl,
        wait,
    )
}

#[cfg(not(target_os = "linux"))]
pub fn send_fake_tcp(
    stream: &TcpStream,
    _original_prefix: &[u8],
    fake_prefix: &[u8],
    ttl: u8,
    md5sig: bool,
    default_ttl: u8,
    _wait: TcpStageWait,
) -> io::Result<()> {
    #[cfg(target_os = "windows")]
    {
        return windows::send_fake_tcp(stream, fake_prefix, ttl, md5sig, default_ttl);
    }
    #[cfg(not(target_os = "windows"))]
    stub::send_fake_tcp(stream, fake_prefix, ttl, md5sig, default_ttl)
}

#[cfg(target_os = "linux")]
pub fn wait_tcp_stage(
    stream: &TcpStream,
    wait_send: bool,
    await_interval: Duration,
) -> io::Result<()> {
    linux::wait_tcp_stage(stream, wait_send, await_interval)
}

#[cfg(not(target_os = "linux"))]
pub fn wait_tcp_stage(
    stream: &TcpStream,
    wait_send: bool,
    await_interval: Duration,
) -> io::Result<()> {
    #[cfg(target_os = "windows")]
    {
        return windows::wait_tcp_stage(stream, wait_send, await_interval);
    }
    #[cfg(not(target_os = "windows"))]
    stub::wait_tcp_stage(stream, wait_send, await_interval)
}

#[cfg(target_os = "linux")]
pub fn tcp_segment_hint(stream: &TcpStream) -> io::Result<Option<TcpSegmentHint>> {
    linux::tcp_segment_hint(stream)
}

#[cfg(not(target_os = "linux"))]
pub fn tcp_segment_hint(_stream: &TcpStream) -> io::Result<Option<TcpSegmentHint>> {
    Ok(None)
}

#[cfg(all(test, not(any(target_os = "linux", target_os = "windows"))))]
mod tests {
    use super::*;
    use std::io::Read;
    use std::net::TcpListener;

    fn connected_pair() -> (TcpStream, TcpStream) {
        let listener = TcpListener::bind(("127.0.0.1", 0)).expect("bind listener");
        let addr = listener.local_addr().expect("listener addr");
        let client = TcpStream::connect(addr).expect("connect client");
        let (server, _) = listener.accept().expect("accept client");
        (client, server)
    }

    #[test]
    fn detect_default_ttl_returns_a_valid_u8() {
        let ttl = detect_default_ttl().expect("detect default ttl");
        assert!(ttl > 0);
    }

    #[test]
    fn public_facade_uses_stub_send_fake_tcp_contract() {
        let (client, mut server) = connected_pair();
        server
            .set_read_timeout(Some(Duration::from_secs(1)))
            .expect("set read timeout");

        send_fake_tcp(
            &client,
            b"original",
            b"fake-prefix",
            0,
            false,
            0,
            (true, Duration::from_millis(1)),
        )
        .expect("send fake tcp through facade");

        let mut buf = [0u8; 11];
        server.read_exact(&mut buf).expect("read fake prefix");
        assert_eq!(&buf, b"fake-prefix");
    }

    #[test]
    fn public_facade_reports_stub_unsupported_calls() {
        let (client, _server) = connected_pair();

        assert_eq!(
            enable_tcp_fastopen_connect(&client)
                .expect_err("tfo should be unsupported")
                .kind(),
            io::ErrorKind::Unsupported
        );
        assert_eq!(
            original_dst(&client)
                .expect_err("original dst should be unsupported")
                .kind(),
            io::ErrorKind::Unsupported
        );
        wait_tcp_stage(&client, true, Duration::from_millis(1)).expect("wait tcp stage no-op");
    }
}
