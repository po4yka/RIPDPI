use tokio::io::{AsyncRead, AsyncWrite, AsyncWriteExt};

use super::FronterError;

pub(super) async fn send_post<S>(stream: &mut S, path: &str, host: &str, body: &[u8]) -> Result<(), FronterError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let request = format!(
        "POST {path} HTTP/1.1\r\nHost: {host}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nAccept-Encoding: gzip\r\nConnection: keep-alive\r\n\r\n",
        body.len()
    );
    stream.write_all(request.as_bytes()).await?;
    stream.write_all(body).await?;
    stream.flush().await?;
    Ok(())
}

pub(super) async fn send_get<S>(stream: &mut S, path: &str, host: &str) -> Result<(), FronterError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let request =
        format!("GET {path} HTTP/1.1\r\nHost: {host}\r\nAccept-Encoding: gzip\r\nConnection: keep-alive\r\n\r\n");
    stream.write_all(request.as_bytes()).await?;
    stream.flush().await?;
    Ok(())
}
