use std::io;
use std::net::SocketAddr;

use tokio::io::{AsyncWrite, AsyncWriteExt};

/// # Cancel safety
///
/// NOT cancel-safe: dropping during `write_all` may leave a prefix of the
/// SOCKS reply on `stream`; callers must close the stream instead of retrying
/// the reply on the same connection.
pub(crate) async fn write_reply<S>(stream: &mut S, reply_code: u8, bound: SocketAddr) -> io::Result<()>
where
    S: AsyncWrite + Unpin,
{
    match bound {
        SocketAddr::V4(addr) => {
            let mut payload = vec![0x05, reply_code, 0x00, 0x01];
            payload.extend_from_slice(&addr.ip().octets());
            payload.extend_from_slice(&addr.port().to_be_bytes());
            stream.write_all(&payload).await
        }
        SocketAddr::V6(addr) => {
            let mut payload = vec![0x05, reply_code, 0x00, 0x04];
            payload.extend_from_slice(&addr.ip().octets());
            payload.extend_from_slice(&addr.port().to_be_bytes());
            stream.write_all(&payload).await
        }
    }
}
