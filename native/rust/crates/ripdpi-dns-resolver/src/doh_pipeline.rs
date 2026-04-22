use std::num::NonZeroUsize;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use hickory_proto::op::Message;
use hickory_proto::rr::RecordType;
use lru::LruCache;
use rustls::pki_types::CertificateDer;

use crate::resolver::EncryptedDnsResolver;
use crate::transport::{build_dns_query, DEFAULT_TIMEOUT};
use crate::types::{EncryptedDnsConnectHooks, EncryptedDnsEndpoint, EncryptedDnsError, EncryptedDnsTransport};

const DEFAULT_CACHE_SIZE: usize = 64;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DohBatchRecordType {
    A,
    Aaaa,
    Cname,
    Https,
    Svcb,
}

impl DohBatchRecordType {
    const ALL: [Self; 5] = [Self::A, Self::Aaaa, Self::Cname, Self::Https, Self::Svcb];

    fn record_type(self) -> RecordType {
        match self {
            Self::A => RecordType::A,
            Self::Aaaa => RecordType::AAAA,
            Self::Cname => RecordType::CNAME,
            Self::Https => RecordType::HTTPS,
            Self::Svcb => RecordType::SVCB,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DohResolverRole {
    Primary,
    Secondary,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DohBatchRecordResponse {
    pub record_type: DohBatchRecordType,
    pub response_bytes: Vec<u8>,
    pub min_ttl_secs: Option<u32>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct DohBatchLookup {
    pub domain: String,
    pub resolver_role: DohResolverRole,
    pub endpoint_label: String,
    pub records: Vec<DohBatchRecordResponse>,
    pub cache_ttl_secs: Option<u32>,
}

struct CachedBatchLookup {
    lookup: DohBatchLookup,
    expires_at: Instant,
}

struct DohResolverPipelineInner {
    primary: EncryptedDnsResolver,
    secondary: EncryptedDnsResolver,
    cache: Mutex<LruCache<String, CachedBatchLookup>>,
}

#[derive(Clone)]
pub struct DohResolverPipeline {
    inner: Arc<DohResolverPipelineInner>,
}

pub struct DohResolverPipelineBuilder {
    primary: Option<EncryptedDnsEndpoint>,
    secondary: Option<EncryptedDnsEndpoint>,
    transport: EncryptedDnsTransport,
    timeout: Duration,
    cache_size: usize,
    tls_roots: Vec<CertificateDer<'static>>,
    connect_hooks: EncryptedDnsConnectHooks,
}

impl DohResolverPipeline {
    pub fn builder() -> DohResolverPipelineBuilder {
        DohResolverPipelineBuilder::new()
    }

    pub async fn resolve(&self, domain: &str) -> Result<DohBatchLookup, EncryptedDnsError> {
        if let Some(cached) = self.cached_lookup(domain) {
            return Ok(cached);
        }

        match self.lookup_with_resolver(domain, DohResolverRole::Primary, &self.inner.primary).await {
            Ok(lookup) => {
                self.cache_lookup(domain, &lookup);
                return Ok(lookup);
            }
            Err(_) => {}
        }

        match self.lookup_with_resolver(domain, DohResolverRole::Secondary, &self.inner.secondary).await {
            Ok(lookup) => {
                self.cache_lookup(domain, &lookup);
                Ok(lookup)
            }
            Err(err) => Err(err),
        }
    }

    pub fn resolve_blocking(&self, domain: &str) -> Result<DohBatchLookup, EncryptedDnsError> {
        if let Some(cached) = self.cached_lookup(domain) {
            return Ok(cached);
        }

        match self.lookup_with_resolver_blocking(domain, DohResolverRole::Primary, &self.inner.primary) {
            Ok(lookup) => {
                self.cache_lookup(domain, &lookup);
                return Ok(lookup);
            }
            Err(_) => {}
        }

        match self.lookup_with_resolver_blocking(domain, DohResolverRole::Secondary, &self.inner.secondary) {
            Ok(lookup) => {
                self.cache_lookup(domain, &lookup);
                Ok(lookup)
            }
            Err(err) => Err(err),
        }
    }

    fn cached_lookup(&self, domain: &str) -> Option<DohBatchLookup> {
        let cache = self.inner.cache.lock().ok()?;
        let cached = cache.peek(domain)?;
        (cached.expires_at > Instant::now()).then(|| cached.lookup.clone())
    }

    fn cache_lookup(&self, domain: &str, lookup: &DohBatchLookup) {
        let Some(ttl_secs) = lookup.cache_ttl_secs.filter(|ttl| *ttl > 0) else {
            return;
        };
        if let Ok(mut cache) = self.inner.cache.lock() {
            cache.put(
                domain.to_string(),
                CachedBatchLookup {
                    lookup: lookup.clone(),
                    expires_at: Instant::now() + Duration::from_secs(u64::from(ttl_secs)),
                },
            );
        }
    }

    async fn lookup_with_resolver(
        &self,
        domain: &str,
        resolver_role: DohResolverRole,
        resolver: &EncryptedDnsResolver,
    ) -> Result<DohBatchLookup, EncryptedDnsError> {
        let mut endpoint_label: Option<String> = None;
        let mut records = Vec::with_capacity(DohBatchRecordType::ALL.len());

        for record_type in DohBatchRecordType::ALL {
            let query = build_dns_query(domain, record_type.record_type())?;
            let success = resolver.exchange_with_metadata(&query).await?;
            endpoint_label.get_or_insert(success.endpoint_label.clone());
            records.push(DohBatchRecordResponse {
                record_type,
                min_ttl_secs: min_ttl_secs(&success.response_bytes),
                response_bytes: success.response_bytes,
            });
        }

        Ok(DohBatchLookup {
            domain: domain.to_string(),
            resolver_role,
            endpoint_label: endpoint_label.unwrap_or_else(|| resolver.endpoint_label()),
            cache_ttl_secs: records.iter().filter_map(|record| record.min_ttl_secs).filter(|ttl| *ttl > 0).min(),
            records,
        })
    }

    fn lookup_with_resolver_blocking(
        &self,
        domain: &str,
        resolver_role: DohResolverRole,
        resolver: &EncryptedDnsResolver,
    ) -> Result<DohBatchLookup, EncryptedDnsError> {
        let mut endpoint_label: Option<String> = None;
        let mut records = Vec::with_capacity(DohBatchRecordType::ALL.len());

        for record_type in DohBatchRecordType::ALL {
            let query = build_dns_query(domain, record_type.record_type())?;
            let success = resolver.exchange_blocking_with_metadata(&query)?;
            endpoint_label.get_or_insert(success.endpoint_label.clone());
            records.push(DohBatchRecordResponse {
                record_type,
                min_ttl_secs: min_ttl_secs(&success.response_bytes),
                response_bytes: success.response_bytes,
            });
        }

        Ok(DohBatchLookup {
            domain: domain.to_string(),
            resolver_role,
            endpoint_label: endpoint_label.unwrap_or_else(|| resolver.endpoint_label()),
            cache_ttl_secs: records.iter().filter_map(|record| record.min_ttl_secs).filter(|ttl| *ttl > 0).min(),
            records,
        })
    }
}

impl std::fmt::Debug for DohResolverPipeline {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.debug_struct("DohResolverPipeline").finish_non_exhaustive()
    }
}

impl Default for DohResolverPipelineBuilder {
    fn default() -> Self {
        Self::new()
    }
}

impl DohResolverPipelineBuilder {
    pub fn new() -> Self {
        Self {
            primary: None,
            secondary: None,
            transport: EncryptedDnsTransport::Direct,
            timeout: DEFAULT_TIMEOUT,
            cache_size: DEFAULT_CACHE_SIZE,
            tls_roots: Vec::new(),
            connect_hooks: EncryptedDnsConnectHooks::default(),
        }
    }

    pub fn primary_endpoint(mut self, endpoint: EncryptedDnsEndpoint) -> Self {
        self.primary = Some(endpoint);
        self
    }

    pub fn secondary_endpoint(mut self, endpoint: EncryptedDnsEndpoint) -> Self {
        self.secondary = Some(endpoint);
        self
    }

    pub fn transport(mut self, transport: EncryptedDnsTransport) -> Self {
        self.transport = transport;
        self
    }

    pub fn timeout(mut self, timeout: Duration) -> Self {
        self.timeout = timeout;
        self
    }

    pub fn cache_size(mut self, size: usize) -> Self {
        self.cache_size = size;
        self
    }

    pub fn tls_roots(mut self, roots: Vec<CertificateDer<'static>>) -> Self {
        self.tls_roots = roots;
        self
    }

    pub fn connect_hooks(mut self, hooks: EncryptedDnsConnectHooks) -> Self {
        self.connect_hooks = hooks;
        self
    }

    pub fn build(self) -> Result<DohResolverPipeline, EncryptedDnsError> {
        let primary = self
            .primary
            .ok_or_else(|| EncryptedDnsError::InvalidEndpoint("missing primary DoH endpoint".to_string()))?;
        let secondary = self
            .secondary
            .ok_or_else(|| EncryptedDnsError::InvalidEndpoint("missing secondary DoH endpoint".to_string()))?;
        ensure_doh_endpoint(&primary)?;
        ensure_doh_endpoint(&secondary)?;

        let cache_size = NonZeroUsize::new(self.cache_size.max(1))
            .unwrap_or_else(|| NonZeroUsize::new(DEFAULT_CACHE_SIZE).expect("non-zero"));

        Ok(DohResolverPipeline {
            inner: Arc::new(DohResolverPipelineInner {
                primary: EncryptedDnsResolver::with_extra_tls_roots_and_connect_hooks(
                    primary,
                    self.transport.clone(),
                    self.timeout,
                    self.tls_roots.clone(),
                    self.connect_hooks.clone(),
                )?,
                secondary: EncryptedDnsResolver::with_extra_tls_roots_and_connect_hooks(
                    secondary,
                    self.transport,
                    self.timeout,
                    self.tls_roots,
                    self.connect_hooks,
                )?,
                cache: Mutex::new(LruCache::new(cache_size)),
            }),
        })
    }
}

fn ensure_doh_endpoint(endpoint: &EncryptedDnsEndpoint) -> Result<(), EncryptedDnsError> {
    (endpoint.protocol == crate::types::EncryptedDnsProtocol::Doh)
        .then_some(())
        .ok_or_else(|| EncryptedDnsError::InvalidEndpoint("DoH pipeline requires DoH endpoints".to_string()))
}

fn min_ttl_secs(response_bytes: &[u8]) -> Option<u32> {
    let message = Message::from_vec(response_bytes).ok()?;
    message
        .answers()
        .iter()
        .chain(message.name_servers().iter())
        .chain(message.additionals().iter())
        .map(|record| record.ttl())
        .min()
}
