use std::io;
use std::net::SocketAddr;

use tokio::net::TcpStream;

pub(super) async fn connect(address: SocketAddr) -> io::Result<TcpStream> {
    TcpStream::connect(address).await
}
