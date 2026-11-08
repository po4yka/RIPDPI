/// Handle the connect command by running a TCP proxy until the connection is done.
/// The timeout covers connection setup and its reply, not the established relay.
///
/// # Cancel safety:
/// NOT cancel-safe: cancellation ends the relay; close any borrowed inbound stream.
pub async fn run_tcp_proxy<T: AsyncRead + AsyncWrite + Unpin>(
    proto: Socks5ServerProtocol<T, states::CommandRead>,
    addr: &TargetAddr,
    request_timeout: Duration,
    nodelay: bool,
) -> Result<T, SocksServerError> {
    let deadline = request_deadline(request_timeout)?;
    run_tcp_proxy_until(proto, addr, deadline, nodelay).await
}

/// # Cancel safety:
/// NOT cancel-safe: cancellation ends setup or relay; close any borrowed inbound stream.
async fn run_tcp_proxy_until<T: AsyncRead + AsyncWrite + Unpin>(
    proto: Socks5ServerProtocol<T, states::CommandRead>,
    addr: &TargetAddr,
    deadline: tokio::time::Instant,
    nodelay: bool,
) -> Result<T, SocksServerError> {
    let (mut inner, outbound) = request_until(deadline, async {
        let addr = try_notify!(proto, addr.socket_addr().ok_or(SocksServerError::Bug("unresolved target address")));

        // TCP connect with timeout, to avoid memory leak for connection that takes forever
        let outbound =
            match tcp_connect_with_timeout(addr, deadline.saturating_duration_since(tokio::time::Instant::now())).await
            {
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
        let inner = proto.reply_success(bound_addr).await?;

        Ok((inner, outbound))
    })
    .await?;

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
