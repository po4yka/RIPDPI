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

pub mod connect {
    use std::io;
    use std::net::TcpStream;

    use socket2::Socket;

    pub fn protect_socket(socket: &Socket, protect_path: &str) -> io::Result<()> {
        ripdpi_runtime_platform::vpn::protect_socket(socket, Some(protect_path))
    }

    pub fn set_rcvbuf(socket: &Socket, rcvbuf: u32) -> io::Result<()> {
        ripdpi_runtime_platform::socket::set_rcvbuf(socket, rcvbuf)
    }

    pub fn tcp_total_retransmissions(socket: &Socket) -> io::Result<Option<u32>> {
        ripdpi_runtime_platform::tcp::tcp_total_retransmissions(socket)
    }

    pub fn enable_tcp_fastopen_connect(socket: &Socket) -> io::Result<()> {
        ripdpi_runtime_platform::socket::enable_tcp_fastopen_connect(socket)
    }

    pub fn attach_drop_sack(stream: &TcpStream) -> io::Result<()> {
        ripdpi_runtime_platform::socket::attach_drop_sack(stream)
    }

    pub fn set_tcp_window_clamp(stream: &TcpStream, clamp: u32) -> io::Result<()> {
        ripdpi_runtime_platform::socket::set_tcp_window_clamp(stream, clamp)
    }

    pub fn attach_strip_timestamps(stream: &TcpStream) -> io::Result<()> {
        ripdpi_runtime_platform::socket::attach_strip_timestamps(stream)
    }

    pub fn tcp_round_trip_time_ms(stream: &TcpStream) -> io::Result<Option<u64>> {
        ripdpi_runtime_platform::tcp::tcp_round_trip_time_ms(stream)
    }
}

pub mod first_response {
    use std::io;
    use std::net::TcpStream;

    pub fn enable_recv_ttl(stream: &TcpStream) -> io::Result<()> {
        ripdpi_runtime_platform::ttl_ops::enable_recv_ttl(stream)
    }

    pub fn read_chunk_with_ttl(stream: &mut TcpStream, chunk: &mut [u8]) -> io::Result<(usize, Option<u8>)> {
        ripdpi_runtime_platform::ttl_ops::read_chunk_with_ttl(stream, chunk)
    }

    pub fn tcp_total_retransmissions(stream: &TcpStream) -> io::Result<Option<u32>> {
        ripdpi_runtime_platform::tcp::tcp_total_retransmissions(stream)
    }
}
