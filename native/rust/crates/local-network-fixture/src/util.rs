use std::io;
use std::net::{TcpStream, UdpSocket};

pub(crate) fn wake_tcp(host: &str, port: u16) {
    let _ = TcpStream::connect((host, port));
}

pub(crate) fn wake_udp(host: &str, port: u16) {
    if let Ok(socket) = UdpSocket::bind(("127.0.0.1", 0)) {
        let _ = socket.send_to(b"wake", (host, port));
    }
}

pub(crate) fn env_string(key: &str, default: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| default.to_string())
}

pub(crate) fn env_u16(key: &str, default: u16) -> u16 {
    std::env::var(key).ok().and_then(|value| value.parse::<u16>().ok()).unwrap_or(default)
}

pub(crate) fn percent_decode(value: &str) -> String {
    value.replace("%2E", ".").replace("%2e", ".").replace("%3A", ":").replace("%3a", ":").replace('+', " ")
}

pub(crate) fn other_io<E>(error: E) -> io::Error
where
    E: ToString,
{
    io::Error::other(error.to_string())
}
