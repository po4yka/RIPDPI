use std::io;
use std::net::TcpStream;

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn attach_strip_timestamps(stream: &TcpStream) -> io::Result<()> {
    ripdpi_privileged_ops::attach_strip_timestamps(stream)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn attach_strip_timestamps(_stream: &TcpStream) -> io::Result<()> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}
