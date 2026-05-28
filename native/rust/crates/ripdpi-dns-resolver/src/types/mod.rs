mod cache;
mod endpoint;
mod error;
mod exchange;
mod hooks;
mod oracle;

pub(crate) use cache::DnsCryptCachedCertificate;
#[allow(unused_imports)]
pub use endpoint::{
    EncryptedDnsEndpoint, EncryptedDnsProtocol, EncryptedDnsTransport, DOQ_DEFAULT_PORT, DOT_DEFAULT_PORT,
};
pub use error::{EncryptedDnsError, EncryptedDnsErrorKind};
pub use exchange::EncryptedDnsExchangeSuccess;
#[allow(unused_imports)]
pub use hooks::{
    BoxedDnsTcpStream, DirectTcpConnection, DirectTcpConnector, DirectTcpConnectorFuture, DirectUdpBinder,
    DnsTcpStream, DotTlsConnectorBuilder, EncryptedDnsConnectHooks,
};
pub use oracle::{ResolverNetworkScope, ResolverOracleObservation};
