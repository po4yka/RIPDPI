use std::io;
use std::net::{SocketAddr, TcpStream};

#[cfg(any(target_os = "linux", target_os = "android"))]
pub fn original_dst(stream: &TcpStream) -> io::Result<SocketAddr> {
    ripdpi_privileged_ops::original_dst(stream)
}

#[cfg(not(any(target_os = "linux", target_os = "android")))]
pub fn original_dst(_stream: &TcpStream) -> io::Result<SocketAddr> {
    Err(io::Error::new(io::ErrorKind::Unsupported, "only supported on Linux/Android"))
}
