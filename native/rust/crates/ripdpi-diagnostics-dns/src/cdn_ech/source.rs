use crate::cdn_ech::catalog::CLOUDFLARE_ECH_CONFIG_LIST;
use crate::cdn_ech::validation::validate_ech_config_list_bytes;

#[derive(Debug)]
pub enum EchSourceError {
    NotImplemented(&'static str),
    InvalidConfig(String),
}

impl std::fmt::Display for EchSourceError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::NotImplemented(message) => write!(formatter, "not implemented: {message}"),
            Self::InvalidConfig(message) => write!(formatter, "invalid ECH config: {message}"),
        }
    }
}

pub trait EchConfigSource: Send + Sync {
    fn fetch(&self) -> Result<Vec<u8>, EchSourceError>;
}

pub struct BundledEchConfigSource;

impl EchConfigSource for BundledEchConfigSource {
    fn fetch(&self) -> Result<Vec<u8>, EchSourceError> {
        Ok(CLOUDFLARE_ECH_CONFIG_LIST.to_vec())
    }
}

const REMOTE_ECH_DEFAULT_DOMAIN: &str = "cloudflare-dns.com";

const REMOTE_ECH_DEFAULT_RESOLVER: &str = "cloudflare";

pub struct RemoteEchConfigSource {
    pub(crate) domain: String,
    pub(crate) resolver_id: String,
}

impl Default for RemoteEchConfigSource {
    fn default() -> Self {
        Self::new()
    }
}

impl RemoteEchConfigSource {
    pub fn new() -> Self {
        Self { domain: REMOTE_ECH_DEFAULT_DOMAIN.to_string(), resolver_id: REMOTE_ECH_DEFAULT_RESOLVER.to_string() }
    }

    pub fn with_domain(mut self, domain: impl Into<String>) -> Self {
        self.domain = domain.into();
        self
    }

    pub fn with_resolver(mut self, resolver_id: impl Into<String>) -> Self {
        self.resolver_id = resolver_id.into();
        self
    }
}

impl EchConfigSource for RemoteEchConfigSource {
    fn fetch(&self) -> Result<Vec<u8>, EchSourceError> {
        use crate::dns::EchResolutionOutcome;
        use crate::dns::encrypted_dns_endpoint_for_resolver_id;
        use crate::transport::TransportConfig;
        use ripdpi_dns_resolver::EncryptedDnsConnectHooks;
        use ripdpi_ech_dns::resolve_https_ech_configs_via_encrypted_dns_with_hooks;

        let endpoint = encrypted_dns_endpoint_for_resolver_id(&self.resolver_id);
        let transport = TransportConfig::Direct { route_experiment: None };
        let hooks = EncryptedDnsConnectHooks::new()
            .require_direct_tcp_connector()
            .with_direct_tcp_socket(ripdpi_diagnostics_transport::transport::protected_tcp_socket);
        let outcome = resolve_https_ech_configs_via_encrypted_dns_with_hooks(&self.domain, endpoint, &transport, hooks);
        let bytes = match outcome {
            EchResolutionOutcome::Available(bytes) => bytes,
            EchResolutionOutcome::NotPublished => {
                return Err(EchSourceError::InvalidConfig(format!(
                    "no ECHConfigList published for {} via {} DoH",
                    self.domain, self.resolver_id
                )));
            }
            EchResolutionOutcome::ResolutionFailed(error) | EchResolutionOutcome::InvalidConfig(error) => {
                return Err(EchSourceError::InvalidConfig(format!(
                    "DoH HTTPS-RR query for {} via {} failed: {error}",
                    self.domain, self.resolver_id
                )));
            }
        };
        validate_ech_config_list_bytes(&bytes)?;
        Ok(bytes)
    }
}
