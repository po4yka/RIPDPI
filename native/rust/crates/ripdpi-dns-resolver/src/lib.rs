#![forbid(unsafe_code)]

mod dnscrypt;
pub mod doh;
mod doh_pipeline;
mod health;
#[cfg(feature = "hickory-backend")]
mod hickory_backend;
mod https_service_binding;
mod odoh;
mod pool;
mod resolver;
mod transport;
mod types;

#[cfg(test)]
mod tests;

// Public API re-exports
pub use doh_pipeline::{
    DohBatchLookup, DohBatchRecordResponse, DohBatchRecordType, DohIpAnswerCandidate, DohIpFamily, DohResolverPipeline,
    DohResolverPipelineBuilder, DohResolverRole, doh_ip_answer_candidates,
};
pub use health::{HealthRegistry, HealthScoreSnapshot};
pub use https_service_binding::{
    EchCipherSuite, EchConfig, EchConfigEntry, EchExtension, HttpsRr, HttpsRrRecordType, HttpsSvcbParseError,
    parse_ech_config_list, parse_https_service_bindings,
};
pub use odoh::{
    ODOH_MESSAGE_MEDIA_TYPE, OdohConfigLookupSecurity, OdohConfigSource, OdohConfigSourceKind, OdohEncryptedQuery,
    OdohError, OdohPlainResponse, OdohQueryContext, OdohResolvedConfig, OdohTargetConfig,
};
pub use pool::{ResolverPool, ResolverPoolBuilder};
pub use resolver::EncryptedDnsResolver;
pub use transport::{IpAnswerFamily, IpAnswerRecord, extract_ip_answer_records, extract_ip_answers};
pub use types::{
    BoxedDnsTcpStream, DirectTcpConnection, EncryptedDnsConnectHooks, EncryptedDnsEndpoint, EncryptedDnsError,
    EncryptedDnsErrorKind, EncryptedDnsExchangeSuccess, EncryptedDnsProtocol, EncryptedDnsTransport,
    OdohEndpointConfig, ResolverNetworkScope, ResolverOracleObservation,
};

// Internal re-exports for test access
#[allow(unused_imports)]
pub(crate) use dnscrypt::{
    DNSCRYPT_CERT_MAGIC, DNSCRYPT_CERT_SIZE, DNSCRYPT_ES_VERSION_XCHACHA, DNSCRYPT_ES_VERSION_XSALSA,
    DNSCRYPT_NONCE_SIZE, DNSCRYPT_PADDING_BLOCK_SIZE, DNSCRYPT_QUERY_NONCE_HALF, DNSCRYPT_RESPONSE_MAGIC,
    DnsCryptCipher, dnscrypt_pad, dnscrypt_unpad, parse_dnscrypt_certificate,
};
#[cfg(test)]
#[allow(unused_imports)]
pub(crate) use transport::{DEFAULT_TIMEOUT, DNS_MESSAGE_MEDIA_TYPE, build_dns_query, unix_time_secs};
#[allow(unused_imports)]
pub(crate) use types::DnsCryptCachedCertificate;
