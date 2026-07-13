use std::io;

use bytes::Bytes;
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::sync::mpsc;

use crate::errors::join_error_to_io;

const STREAM_BUFFER_SIZE: usize = 16 * 1024;

pub(crate) async fn run_session(
    mut inbound_rx: mpsc::Receiver<Bytes>,
    outbound_tx: mpsc::Sender<io::Result<Bytes>>,
    expected_uuid: [u8; 16],
    protect_path: Option<&str>,
) -> io::Result<()> {
    let (decoded, buffered_payload) = read_request_header(&mut inbound_rx).await?;
    if decoded.uuid != expected_uuid {
        return Err(io::Error::new(
            io::ErrorKind::PermissionDenied,
            "VLESS UUID does not match configured tunnel identity",
        ));
    }

    let upstream = ripdpi_subprocess_protect::protected_tcp_connect(decoded.target.as_str(), protect_path)
        .await
        .map_err(redact_upstream_connect_error)?;
    upstream.set_nodelay(true)?;
    if outbound_tx.send(Ok(Bytes::from(ripdpi_vless::wire::encode_response(&[])?))).await.is_err() {
        return Ok(());
    }

    let (mut upstream_reader, mut upstream_writer) = upstream.into_split();
    let upload = tokio::spawn(async move {
        if !buffered_payload.is_empty() {
            upstream_writer.write_all(&buffered_payload).await?;
        }
        while let Some(chunk) = inbound_rx.recv().await {
            upstream_writer.write_all(&chunk).await?;
        }
        upstream_writer.shutdown().await
    });
    let download = tokio::spawn(async move {
        let mut buffer = vec![0u8; STREAM_BUFFER_SIZE];
        loop {
            let read = upstream_reader.read(&mut buffer).await?;
            if read == 0 {
                break;
            }
            if outbound_tx.send(Ok(Bytes::copy_from_slice(&buffer[..read]))).await.is_err() {
                break;
            }
        }
        Ok::<(), io::Error>(())
    });

    upload.await.map_err(join_error_to_io)??;
    download.await.map_err(join_error_to_io)??;
    Ok(())
}

fn redact_upstream_connect_error(error: io::Error) -> io::Error {
    io::Error::new(error.kind(), "upstream connect failed")
}

async fn read_request_header(
    inbound_rx: &mut mpsc::Receiver<Bytes>,
) -> io::Result<(ripdpi_vless::wire::DecodedRequestHeader, Vec<u8>)> {
    let mut buffered = Vec::new();
    loop {
        match ripdpi_vless::wire::parse_request_header(&buffered) {
            Ok(decoded) => {
                let remaining = buffered.split_off(decoded.consumed_len);
                return Ok((decoded, remaining));
            }

            Err(ripdpi_vless::wire::ParseRequestError::NeedMoreData) => {
                let Some(chunk) = inbound_rx.recv().await else {
                    return Err(io::Error::new(
                        io::ErrorKind::UnexpectedEof,
                        "xHTTP POST stream ended before the VLESS request header completed",
                    ));
                };
                buffered.extend_from_slice(&chunk);
            }

            Err(error) => return Err(io::Error::new(io::ErrorKind::InvalidData, error)),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::redact_upstream_connect_error;

    #[test]
    fn upstream_connect_error_does_not_export_destination_or_provider_text() {
        let error = std::io::Error::new(
            std::io::ErrorKind::ConnectionRefused,
            "connect private.example:443 with credential-shaped-provider-text",
        );

        let redacted = redact_upstream_connect_error(error);

        assert_eq!(redacted.kind(), std::io::ErrorKind::ConnectionRefused);
        assert_eq!(redacted.to_string(), "upstream connect failed");
    }
}
