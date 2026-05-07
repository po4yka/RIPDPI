pub use ripdpi_runtime_platform::*;

pub mod relay {
    use std::io;
    use std::net::TcpStream;

    pub fn tcp_total_retransmissions(stream: &TcpStream) -> io::Result<Option<u32>> {
        ripdpi_runtime_platform::tcp::tcp_total_retransmissions(stream)
    }

    pub fn detach_drop_sack(stream: &TcpStream) -> io::Result<()> {
        ripdpi_runtime_platform::socket::detach_drop_sack(stream)
    }
}
