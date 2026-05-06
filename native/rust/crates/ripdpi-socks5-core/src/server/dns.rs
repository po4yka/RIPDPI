trait DnsResolveHelper
where
    Self: Sized,
{
    async fn resolve_dns(self) -> Result<Self, SocksServerError>;
}

impl<T> DnsResolveHelper for (Socks5ServerProtocol<T, states::CommandRead>, Socks5Command, TargetAddr)
where
    T: AsyncRead + AsyncWrite + Unpin,
{
    async fn resolve_dns(self) -> Result<Self, SocksServerError> {
        let (proto, cmd, target_addr) = self;
        let resolved_addr = try_notify!(proto, target_addr.resolve_dns().await);
        Ok((proto, cmd, resolved_addr))
    }
}
