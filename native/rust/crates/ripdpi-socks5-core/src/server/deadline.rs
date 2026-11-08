fn request_deadline(timeout: Duration) -> Result<tokio::time::Instant, SocksServerError> {
    tokio::time::Instant::now().checked_add(timeout).ok_or_else(|| SocksServerError::Io {
        source: io::Error::new(io::ErrorKind::InvalidInput, "SOCKS request timeout is too large"),
        context: "setting request deadline",
    })
}

/// # Cancel safety:
/// NOT cancel-safe: expiry or cancellation drops the request, which must release its resources.
/// Discard any borrowed transport after interrupted I/O.
async fn request_until<R>(
    deadline: tokio::time::Instant,
    request: impl Future<Output = Result<R, SocksServerError>>,
) -> Result<R, SocksServerError> {
    tokio::time::timeout_at(deadline, request).await.map_err(|_| SocksServerError::Io {
        source: io::Error::new(io::ErrorKind::TimedOut, "SOCKS request timed out"),
        context: "completing request",
    })?
}
