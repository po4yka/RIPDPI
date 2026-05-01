use crate::cdn_ech::catalog::CLOUDFLARE_ECH_CONFIG_LIST;
use crate::cdn_ech::validation::validate_ech_config_list_bytes;

/// Error type returned by [`EchConfigSource::fetch`].
#[derive(Debug)]
pub enum EchSourceError {
    /// The source is unavailable in this build.
    NotImplemented(&'static str),
    /// The fetched bytes failed structural validation.
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

/// A source that can produce a raw ECHConfigList (wire-format bytes).
///
/// Implementations are expected to be cheap to call when the config is already
/// in hand (e.g. returning a static slice copy). Expensive operations belong in
/// a higher-level scheduler that calls [`crate::cdn_ech::CdnEchUpdater::refresh`].
pub trait EchConfigSource: Send + Sync {
    /// Return the current ECHConfigList bytes, or an error.
    fn fetch(&self) -> Result<Vec<u8>, EchSourceError>;
}

/// Returns the bundled Cloudflare ECHConfigList.
///
/// This is the production fallback: always available, never fails.
pub struct BundledEchConfigSource;

impl EchConfigSource for BundledEchConfigSource {
    fn fetch(&self) -> Result<Vec<u8>, EchSourceError> {
        Ok(CLOUDFLARE_ECH_CONFIG_LIST.to_vec())
    }
}

/// Default domain queried for the Cloudflare ECH config. The HTTPS resource
/// record on this name is published by Cloudflare and rotated on the same
/// cadence as the runtime ECH key.
const REMOTE_ECH_DEFAULT_DOMAIN: &str = "cloudflare-dns.com";

/// Default DoH resolver used by [`RemoteEchConfigSource`]. Cloudflare's own
/// public resolver is the correct source for the ECH config we are looking up.
const REMOTE_ECH_DEFAULT_RESOLVER: &str = "cloudflare";

/// Live DoH-based remote source for the Cloudflare ECH config.
///
/// `fetch` performs an HTTPS-RR (type 65) query for `domain` against the
/// resolver named by `resolver_id` using the existing encrypted-DNS plumbing in
/// [`crate::dns`]. Any failure path surfaces as [`EchSourceError::InvalidConfig`]
/// so [`crate::cdn_ech::CdnEchUpdater`] can fall back to the bundled config
/// without dropping ECH support entirely.
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
    /// Construct a source that queries Cloudflare's published ECH config via
    /// Cloudflare's own DoH resolver.
    pub fn new() -> Self {
        Self { domain: REMOTE_ECH_DEFAULT_DOMAIN.to_string(), resolver_id: REMOTE_ECH_DEFAULT_RESOLVER.to_string() }
    }

    /// Override the queried HTTPS-RR domain (test-only / future tuning).
    pub fn with_domain(mut self, domain: impl Into<String>) -> Self {
        self.domain = domain.into();
        self
    }

    /// Override the DoH resolver id (`cloudflare`, `quad9`, ...) used to
    /// satisfy the lookup.
    pub fn with_resolver(mut self, resolver_id: impl Into<String>) -> Self {
        self.resolver_id = resolver_id.into();
        self
    }
}

impl EchConfigSource for RemoteEchConfigSource {
    fn fetch(&self) -> Result<Vec<u8>, EchSourceError> {
        use crate::dns::EchResolutionOutcome;
        use crate::dns::{
            encrypted_dns_endpoint_for_resolver_id, resolve_https_ech_configs_via_encrypted_dns_with_endpoint,
        };
        use crate::transport::TransportConfig;

        let endpoint = encrypted_dns_endpoint_for_resolver_id(&self.resolver_id);
        let transport = TransportConfig::Direct { route_experiment: None };
        let outcome = resolve_https_ech_configs_via_encrypted_dns_with_endpoint(&self.domain, endpoint, &transport);
        let bytes = match outcome {
            EchResolutionOutcome::Available(bytes) => bytes,
            EchResolutionOutcome::NotPublished => {
                return Err(EchSourceError::InvalidConfig(format!(
                    "no ECHConfigList published for {} via {} DoH",
                    self.domain, self.resolver_id
                )));
            }
            EchResolutionOutcome::ResolutionFailed(error) => {
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
