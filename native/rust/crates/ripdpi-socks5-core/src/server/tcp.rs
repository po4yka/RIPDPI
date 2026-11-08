/// Handle the connect command by running a TCP proxy until the connection is done.
///
/// # Cancel safety:
/// NOT cancel-safe: cancellation ends the relay; callers must close any borrowed inbound stream.
pub async fn run_tcp_proxy<T: AsyncRead + AsyncWrite + Unpin>(
    proto: Socks5ServerProtocol<T, states::CommandRead>,
    addr: &TargetAddr,
    request_timeout: Duration,
    nodelay: bool,
) -> Result<T, SocksServerError> {
    let addr = try_notify!(proto, addr.socket_addr().ok_or(SocksServerError::Bug("unresolved target address")));

    // TCP connect with timeout, to avoid memory leak for connection that takes forever
    let outbound = match tcp_connect_with_timeout(addr, request_timeout).await {
        Ok(stream) => stream,
        Err(err) => {
            proto.reply_error(&err.to_reply_error()).await?;
            return Err(err.into());
        }
    };

    // Disable Nagle's algorithm if config specifies to do so.
    try_notify!(proto, outbound.set_nodelay(nodelay).err_when("setting nodelay"));

    debug!("Connected to remote destination");

    let bound_addr = try_notify!(proto, outbound.local_addr().err_when("reading outbound local address"));
    let mut inner = proto.reply_success(bound_addr).await?;

    transfer(&mut inner, outbound).await;
    Ok(inner)
}

/// Run a bidirectional proxy between two streams.
/// Using 2 different generators, because they could be different structs with same traits.
pub async fn transfer<I, O>(mut inbound: I, mut outbound: O)
where
    I: AsyncRead + AsyncWrite + Unpin,
    O: AsyncRead + AsyncWrite + Unpin,
{
    match tokio::io::copy_bidirectional(&mut inbound, &mut outbound).await {
        Ok(res) => debug!("transfer closed ({}, {})", res.0, res.1),
        Err(_) => error!("SOCKS TCP transfer failed"),
    };
}

#[cfg(test)]
mod tcp_endpoint_tests {
    use super::*;

    include!("tcp_endpoint_tests.rs");
}
