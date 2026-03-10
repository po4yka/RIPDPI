use std::io;
use std::net::SocketAddr;
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::net::TcpStream;
use tokio_util::sync::CancellationToken;

use crate::socks5::{Auth, TargetAddr};

/// High-level TCP session: connect to SOCKS5 proxy, perform handshake,
/// issue CONNECT to the target, then bidirectionally relay bytes until
/// one side closes or `cancel` is signalled.
pub struct TcpSession {
    proxy_addr: SocketAddr,
    auth: Auth,
    target: TargetAddr,
}

impl TcpSession {
    pub fn new(proxy_addr: SocketAddr, auth: Auth, target: TargetAddr) -> Self {
        Self {
            proxy_addr,
            auth,
            target,
        }
    }

    /// Run the session to completion.
    ///
    /// - Connects to the SOCKS5 proxy at `proxy_addr`.
    /// - Performs SOCKS5 handshake (method negotiation + optional auth).
    /// - Issues a SOCKS5 CONNECT request to `target`.
    /// - Bidirectionally splices `local` ↔ proxy until EOF on both sides or
    ///   until `cancel` is signalled (in which case the function returns `Ok(())`).
    pub async fn run<L>(&self, local: &mut L, cancel: CancellationToken) -> io::Result<()>
    where
        L: AsyncRead + AsyncWrite + Unpin,
    {
        let mut proxy = TcpStream::connect(self.proxy_addr).await?;
        crate::socks5::handshake(&mut proxy, &self.auth).await?;
        crate::socks5::connect(&mut proxy, &self.target).await?;
        tokio::select! {
            result = splice(local, &mut proxy) => result.map(|_| ()),
            _ = cancel.cancelled() => Ok(()),
        }
    }
}

/// Bidirectionally splice bytes between `local` and `proxy` until both sides close.
///
/// Returns `(forward_bytes, backward_bytes)`:
/// - `forward_bytes`:  bytes copied local → proxy
/// - `backward_bytes`: bytes copied proxy → local
///
/// When one read side returns EOF, the opposite write side is shut down (half-close),
/// matching RFC 1928 §6 session semantics.  The function returns only when both
/// directions have finished.
pub async fn splice<L, P>(local: &mut L, proxy: &mut P) -> io::Result<(u64, u64)>
where
    L: AsyncRead + AsyncWrite + Unpin,
    P: AsyncRead + AsyncWrite + Unpin,
{
    tokio::io::copy_bidirectional(local, proxy).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{duplex, AsyncReadExt, AsyncWriteExt};

    // ─────────────────────────────────────────────────────────────
    // Helpers
    // ─────────────────────────────────────────────────────────────

    /// Write `data` into a DuplexStream and shut down its write half.
    /// The peer's read half will see `data` followed by EOF.
    async fn feed_and_close(stream: &mut tokio::io::DuplexStream, data: &[u8]) {
        stream.write_all(data).await.unwrap();
        stream.shutdown().await.unwrap();
    }

    // ─────────────────────────────────────────────────────────────
    // Forward splice: local → proxy
    // ─────────────────────────────────────────────────────────────

    /// 1 MiB of data written into the local side must arrive at the proxy side
    /// intact, and `splice` must report the correct forward byte count.
    #[tokio::test]
    async fn splice_forward_1mb() {
        const SIZE: usize = 1_048_576;
        let payload: Vec<u8> = (0..SIZE as u32).map(|i| i as u8).collect();

        // Buffers large enough that a 1 MiB write never blocks in a single-
        // threaded test runtime.
        let (mut local, mut test_local) = duplex(SIZE + 1);
        let (mut proxy, mut test_proxy) = duplex(SIZE + 1);

        // Forward source: 1 MiB into local's read buffer, then EOF.
        feed_and_close(&mut test_local, &payload).await;
        // Backward source: no data, just EOF on proxy's read side.
        test_proxy.shutdown().await.unwrap();

        let (fwd, bwd) = splice(&mut local, &mut proxy).await.unwrap();

        // Ensure write sides are closed so read_to_end() below terminates.
        local.shutdown().await.ok();
        proxy.shutdown().await.ok();

        let mut received = vec![];
        test_proxy.read_to_end(&mut received).await.unwrap();

        assert_eq!(fwd, SIZE as u64, "forward byte count must equal 1 MiB");
        assert_eq!(bwd, 0, "no backward bytes expected");
        assert_eq!(
            received, payload,
            "forward data must arrive at proxy intact"
        );
    }

    // ─────────────────────────────────────────────────────────────
    // Backward splice: proxy → local
    // ─────────────────────────────────────────────────────────────

    /// 1 MiB of data written into the proxy side must arrive at the local side
    /// intact, and `splice` must report the correct backward byte count.
    #[tokio::test]
    async fn splice_backward_1mb() {
        const SIZE: usize = 1_048_576;
        let payload: Vec<u8> = (0..SIZE as u32).map(|i| i.wrapping_mul(3) as u8).collect();

        let (mut local, mut test_local) = duplex(SIZE + 1);
        let (mut proxy, mut test_proxy) = duplex(SIZE + 1);

        // Forward source: no data.
        test_local.shutdown().await.unwrap();
        // Backward source: 1 MiB into proxy's read buffer, then EOF.
        feed_and_close(&mut test_proxy, &payload).await;

        let (fwd, bwd) = splice(&mut local, &mut proxy).await.unwrap();

        local.shutdown().await.ok();
        proxy.shutdown().await.ok();

        let mut received = vec![];
        test_local.read_to_end(&mut received).await.unwrap();

        assert_eq!(fwd, 0, "no forward bytes expected");
        assert_eq!(bwd, SIZE as u64, "backward byte count must equal 1 MiB");
        assert_eq!(
            received, payload,
            "backward data must arrive at local intact"
        );
    }

    // ─────────────────────────────────────────────────────────────
    // EOF propagation
    // ─────────────────────────────────────────────────────────────

    /// When the proxy closes its write side (EOF), all in-flight data must be
    /// delivered to the local side before `splice` returns.
    #[tokio::test]
    async fn eof_from_proxy_propagates() {
        let data = b"proxy-initiated-close-payload";

        let (mut local, mut test_local) = duplex(1024);
        let (mut proxy, mut test_proxy) = duplex(1024);

        // Backward source: small payload then EOF from proxy.
        feed_and_close(&mut test_proxy, data).await;
        // Forward source: no data.
        test_local.shutdown().await.unwrap();

        let (_fwd, bwd) = splice(&mut local, &mut proxy).await.unwrap();

        local.shutdown().await.ok();
        proxy.shutdown().await.ok();

        let mut received = vec![];
        test_local.read_to_end(&mut received).await.unwrap();

        assert_eq!(
            bwd,
            data.len() as u64,
            "all backward bytes must be counted on proxy EOF"
        );
        assert_eq!(
            received, data,
            "proxy EOF must not drop in-flight data before delivering to local"
        );
    }

    /// When the local side closes its write side (EOF), all in-flight data must
    /// be delivered to the proxy before `splice` returns.
    #[tokio::test]
    async fn eof_from_local_propagates() {
        let data = b"local-initiated-close-payload";

        let (mut local, mut test_local) = duplex(1024);
        let (mut proxy, mut test_proxy) = duplex(1024);

        // Forward source: small payload then EOF from local.
        feed_and_close(&mut test_local, data).await;
        // Backward source: no data.
        test_proxy.shutdown().await.unwrap();

        let (fwd, _bwd) = splice(&mut local, &mut proxy).await.unwrap();

        local.shutdown().await.ok();
        proxy.shutdown().await.ok();

        let mut received = vec![];
        test_proxy.read_to_end(&mut received).await.unwrap();

        assert_eq!(
            fwd,
            data.len() as u64,
            "all forward bytes must be counted on local EOF"
        );
        assert_eq!(
            received, data,
            "local EOF must not drop in-flight data before delivering to proxy"
        );
    }
}
