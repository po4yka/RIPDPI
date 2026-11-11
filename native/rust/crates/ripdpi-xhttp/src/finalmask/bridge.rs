use std::io;
use std::pin::Pin;
use std::sync::Arc;
use std::task::{Context, Poll};

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, DuplexStream, ReadBuf};
use tokio::sync::watch;
use tokio::task::JoinHandle;

use crate::config::{AsyncIo, FinalmaskConfig};
use crate::owned_io::OwnedIo;

use super::masks::{TcpInboundMask, TcpOutboundMask};
use super::spec::FinalmaskSpec;

const TCP_BRIDGE_BUFFER_SIZE: usize = 64 * 1024;
const TCP_COPY_BUFFER_SIZE: usize = 16 * 1024;

type BoxedIo = Box<dyn AsyncIo>;

struct BridgeTask(JoinHandle<()>);

impl Drop for BridgeTask {
    fn drop(&mut self) {
        self.0.abort();
    }
}

struct BridgeIo {
    io: DuplexStream,
    failure: watch::Receiver<Option<Arc<io::Error>>>,
}

impl BridgeIo {
    fn error(&self) -> Option<io::Error> {
        self.failure.borrow().as_ref().map(|error| io::Error::new(error.kind(), Arc::clone(error)))
    }
}

impl AsyncRead for BridgeIo {
    fn poll_read(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        if let Some(error) = self.error() {
            return Poll::Ready(Err(error));
        }
        let before = buf.filled().len();
        let result = Pin::new(&mut self.io).poll_read(cx, buf);
        // A pump can fail between the initial check and the duplex poll.
        if buf.filled().len() == before
            && let Some(error) = self.error()
        {
            return Poll::Ready(Err(error));
        }
        result
    }
}

impl AsyncWrite for BridgeIo {
    fn poll_write(mut self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        if let Some(error) = self.error() {
            return Poll::Ready(Err(error));
        }
        let result = Pin::new(&mut self.io).poll_write(cx, buf);
        if !matches!(result, Poll::Ready(Ok(_)))
            && let Some(error) = self.error()
        {
            return Poll::Ready(Err(error));
        }
        result
    }

    fn poll_flush(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        if let Some(error) = self.error() {
            return Poll::Ready(Err(error));
        }
        Pin::new(&mut self.io).poll_flush(cx)
    }

    fn poll_shutdown(mut self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        if let Some(error) = self.error() {
            return Poll::Ready(Err(error));
        }
        Pin::new(&mut self.io).poll_shutdown(cx)
    }
}

pub fn wrap_tcp_stream<S>(stream: S, config: &FinalmaskConfig) -> io::Result<BoxedIo>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    let Some(spec) = FinalmaskSpec::from_config(config)? else {
        return Ok(Box::new(stream));
    };

    let (app_side, bridge_side) = tokio::io::duplex(TCP_BRIDGE_BUFFER_SIZE);
    let (mut bridge_read, mut bridge_write) = tokio::io::split(bridge_side);
    let (mut upstream_read, mut upstream_write) = tokio::io::split(stream);
    let mut outbound = TcpOutboundMask::new(spec.clone());
    let mut inbound = TcpInboundMask::new(spec);
    let (failure_tx, failure) = watch::channel(None);

    // Cancellation discards this entire carrier, including any partial frame.
    let task = tokio::spawn(async move {
        let uplink = async {
            let mut buffer = vec![0u8; TCP_COPY_BUFFER_SIZE];
            loop {
                let read = bridge_read.read(&mut buffer).await?;
                if read == 0 {
                    upstream_write.shutdown().await?;
                    return Ok::<(), io::Error>(());
                }
                for frame in outbound.encode(&buffer[..read])? {
                    upstream_write.write_all(&frame).await?;
                }
            }
        };
        let downlink = async {
            let mut buffer = vec![0u8; TCP_COPY_BUFFER_SIZE];
            loop {
                let read = upstream_read.read(&mut buffer).await?;
                if read == 0 {
                    bridge_write.shutdown().await?;
                    return Ok::<(), io::Error>(());
                }
                let decoded = inbound.decode(&buffer[..read])?;
                if !decoded.is_empty() {
                    bridge_write.write_all(&decoded).await?;
                }
            }
        };
        // EOF closes one direction; a terminal error discards the sibling pump.
        if let Err(error) = tokio::try_join!(uplink, downlink) {
            failure_tx.send_replace(Some(Arc::new(error)));
        }
    });

    Ok(Box::new(OwnedIo::new(BridgeIo { io: app_side, failure }, BridgeTask(task))))
}

#[cfg(test)]
mod tests;
