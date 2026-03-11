use std::io;
use std::net::{SocketAddr, TcpStream};
use std::time::Duration;

use socket2::{Domain, Protocol, Socket, Type};

#[cfg(target_os = "linux")]
mod linux;

pub type TcpStageWait = (bool, Duration);

pub fn detect_default_ttl() -> io::Result<u8> {
    let socket = Socket::new(Domain::IPV4, Type::STREAM, Some(Protocol::TCP))?;
    let ttl = socket.ttl()?;
    u8::try_from(ttl).map_err(|_| io::Error::new(io::ErrorKind::InvalidData, "socket ttl exceeds u8"))
}

#[cfg(target_os = "linux")]
pub fn enable_tcp_fastopen_connect<T: std::os::fd::AsRawFd>(socket: &T) -> io::Result<()> {
    linux::enable_tcp_fastopen_connect(socket)
}

#[cfg(not(target_os = "linux"))]
pub fn enable_tcp_fastopen_connect<T>(_socket: &T) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(target_os = "linux")]
pub fn set_tcp_md5sig(stream: &TcpStream, key_len: u16) -> io::Result<()> {
    linux::set_tcp_md5sig(stream, key_len)
}

#[cfg(not(target_os = "linux"))]
pub fn set_tcp_md5sig(_stream: &TcpStream, _key_len: u16) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(target_os = "linux")]
pub fn protect_socket<T: std::os::fd::AsRawFd>(socket: &T, path: &str) -> io::Result<()> {
    linux::protect_socket(socket, path)
}

#[cfg(not(target_os = "linux"))]
pub fn protect_socket<T>(_socket: &T, _path: &str) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(target_os = "linux")]
pub fn original_dst(stream: &TcpStream) -> io::Result<SocketAddr> {
    linux::original_dst(stream)
}

#[cfg(not(target_os = "linux"))]
pub fn original_dst(_stream: &TcpStream) -> io::Result<SocketAddr> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(target_os = "linux")]
pub fn attach_drop_sack(stream: &TcpStream) -> io::Result<()> {
    linux::attach_drop_sack(stream)
}

#[cfg(not(target_os = "linux"))]
pub fn attach_drop_sack(_stream: &TcpStream) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(target_os = "linux")]
pub fn detach_drop_sack(stream: &TcpStream) -> io::Result<()> {
    linux::detach_drop_sack(stream)
}

#[cfg(not(target_os = "linux"))]
pub fn detach_drop_sack(_stream: &TcpStream) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
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
    linux::send_fake_tcp(stream, original_prefix, fake_prefix, ttl, md5sig, default_ttl, wait)
}

#[cfg(not(target_os = "linux"))]
pub fn send_fake_tcp(
    _stream: &TcpStream,
    _original_prefix: &[u8],
    _fake_prefix: &[u8],
    _ttl: u8,
    _md5sig: bool,
    _default_ttl: u8,
    _wait: TcpStageWait,
) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}

#[cfg(target_os = "linux")]
pub fn wait_tcp_stage(stream: &TcpStream, wait_send: bool, await_interval: Duration) -> io::Result<()> {
    linux::wait_tcp_stage(stream, wait_send, await_interval)
}

#[cfg(not(target_os = "linux"))]
pub fn wait_tcp_stage(_stream: &TcpStream, _wait_send: bool, _await_interval: Duration) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}
