use std::io;

use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

use crate::config::{AsyncIo, FinalmaskConfig};

use super::masks::{TcpInboundMask, TcpOutboundMask};
use super::spec::FinalmaskSpec;

const TCP_BRIDGE_BUFFER_SIZE: usize = 64 * 1024;
const TCP_COPY_BUFFER_SIZE: usize = 16 * 1024;

type BoxedIo = Box<dyn AsyncIo>;

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

    tokio::spawn(async move {
        let result = async {
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
        }
        .await;
        if let Err(error) = result {
            tracing::debug!(error = %error, "xHTTP finalmask uplink bridge stopped");
        }
    });

    tokio::spawn(async move {
        let result = async {
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
        }
        .await;
        if let Err(error) = result {
            tracing::debug!(error = %error, "xHTTP finalmask downlink bridge stopped");
        }
    });

    Ok(Box::new(app_side))
}
