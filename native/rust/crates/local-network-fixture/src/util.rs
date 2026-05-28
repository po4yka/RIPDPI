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

pub(crate) fn percent_decode(value: &str) -> String {
    let mut decoded = value.replace('+', " ");
    for (encoded, replacement) in [("%2E", "."), ("%2e", "."), ("%2F", "/"), ("%2f", "/"), ("%3A", ":"), ("%3a", ":")] {
        decoded = decoded.replace(encoded, replacement);
    }
    decoded
}

pub(crate) fn other_io(error: impl ToString) -> io::Error {
    io::Error::other(error.to_string())
}
