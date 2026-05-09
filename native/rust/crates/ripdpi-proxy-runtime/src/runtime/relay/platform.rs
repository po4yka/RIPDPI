use std::io;
use std::net::TcpStream;

use ripdpi_proxy_runtime_adapter::platform::{first_response as first_response_platform, relay as relay_platform};

pub(super) fn enable_recv_ttl(stream: &TcpStream) -> io::Result<()> {
    first_response_platform::enable_recv_ttl(stream)
}

pub(super) fn read_chunk_with_ttl(stream: &mut TcpStream, chunk: &mut [u8]) -> io::Result<(usize, Option<u8>)> {
    first_response_platform::read_chunk_with_ttl(stream, chunk)
}

pub(super) fn first_response_tcp_total_retransmissions(stream: &TcpStream) -> io::Result<Option<u32>> {
    first_response_platform::tcp_total_retransmissions(stream)
}

pub(super) fn relay_tcp_total_retransmissions(stream: &TcpStream) -> io::Result<Option<u32>> {
    relay_platform::tcp_total_retransmissions(stream)
}

pub(super) fn detach_drop_sack(stream: &TcpStream) -> io::Result<()> {
    relay_platform::detach_drop_sack(stream)
}
