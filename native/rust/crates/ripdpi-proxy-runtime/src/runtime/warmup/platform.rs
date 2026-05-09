use std::io;
use std::net::TcpStream;

use ripdpi_proxy_runtime_adapter::platform::warmup as warmup_platform;

pub(super) fn enable_recv_ttl(stream: &TcpStream) -> io::Result<()> {
    warmup_platform::enable_recv_ttl(stream)
}
