mod dnscrypt;
mod health;
mod pool;
mod resolver;
mod transport;
mod types;

#[cfg(test)]
mod tests;

// Public API re-exports
pub use health::{HealthRegistry, HealthScoreSnapshot};
pub use pool::{ResolverPool, ResolverPoolBuilder};
pub use resolver::EncryptedDnsResolver;
pub use transport::extract_ip_answers;
pub use types::{
    EncryptedDnsEndpoint, EncryptedDnsError, EncryptedDnsErrorKind, EncryptedDnsExchangeSuccess, EncryptedDnsProtocol,
    EncryptedDnsTransport,
};

// Internal re-exports for test access
#[allow(unused_imports)]
pub(crate) use dnscrypt::{
    dnscrypt_pad, dnscrypt_unpad, parse_dnscrypt_certificate, DNSCRYPT_CERT_MAGIC, DNSCRYPT_CERT_SIZE,
    DNSCRYPT_ES_VERSION, DNSCRYPT_NONCE_SIZE, DNSCRYPT_PADDING_BLOCK_SIZE, DNSCRYPT_QUERY_NONCE_HALF,
    DNSCRYPT_RESPONSE_MAGIC,
};
#[cfg(test)]
#[allow(unused_imports)]
pub(crate) use transport::{
    build_dns_query, read_length_prefixed_frame, unix_time_secs, write_length_prefixed_frame, DEFAULT_TIMEOUT,
    DNS_MESSAGE_MEDIA_TYPE,
};
#[allow(unused_imports)]
pub(crate) use types::DnsCryptCachedCertificate;
