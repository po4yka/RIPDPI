use bytes::{Buf, Bytes};
use tokio::io::{AsyncReadExt, AsyncWriteExt};

use crate::client::AsyncIo;

pub(super) fn spawn_h3_bridge(
    mut stream: h3::client::RequestStream<h3_quinn::BidiStream<Bytes>, Bytes>,
) -> impl AsyncIo {
    let (app_io, bridge_io) = tokio::io::duplex(64 * 1024);
    let (mut bridge_reader, mut bridge_writer) = tokio::io::split(bridge_io);

    tokio::spawn(async move {
        let mut send_buffer = vec![0u8; 16 * 1024];
        loop {
            tokio::select! {
                received = stream.recv_data() => {
                    match received {
                        Ok(Some(mut data)) => {
                            let bytes = data.copy_to_bytes(data.remaining());
                            if let Err(error) = bridge_writer.write_all(&bytes).await {
                                tracing::debug!(error = %error, "MASQUE H3 TCP bridge writer closed");
                                break;
                            }
                        }
                        Ok(None) => break,
                        Err(error) => {
                            tracing::debug!(error = %error, "MASQUE H3 TCP bridge recv error");
                            break;
                        }
                    }
                }
                read = bridge_reader.read(&mut send_buffer) => {
                    match read {
                        Ok(0) => break,
                        Ok(count) => {
                            if let Err(error) = stream.send_data(Bytes::copy_from_slice(&send_buffer[..count])).await {
                                tracing::debug!(error = %error, "MASQUE H3 TCP bridge send error");
                                break;
                            }
                        }
                        Err(error) => {
                            tracing::debug!(error = %error, "MASQUE H3 TCP bridge reader closed");
                            break;
                        }
                    }
                }
            }
        }
        let _ = stream.finish().await;
    });

    app_io
}
