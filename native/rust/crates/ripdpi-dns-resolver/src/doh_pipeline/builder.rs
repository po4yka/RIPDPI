use std::num::NonZeroUsize;
use std::sync::Arc;
use std::time::Duration;

use rustls::pki_types::CertificateDer;

use crate::resolver::EncryptedDnsResolver;
use crate::transport::DEFAULT_TIMEOUT;
use crate::types::{
    EncryptedDnsConnectHooks, EncryptedDnsEndpoint, EncryptedDnsError, EncryptedDnsProtocol, EncryptedDnsTransport,
};

use super::cache::LookupCache;
use super::{DohResolverPipeline, DohResolverPipelineInner};

const DEFAULT_CACHE_SIZE: usize = 64;

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

        let cache_size = NonZeroUsize::new(self.cache_size.max(1)).expect("cache size is at least one");

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
                cache: LookupCache::new(cache_size),
            }),
        })
    }
}

fn ensure_doh_endpoint(endpoint: &EncryptedDnsEndpoint) -> Result<(), EncryptedDnsError> {
    (endpoint.protocol == EncryptedDnsProtocol::Doh)
        .then_some(())
        .ok_or_else(|| EncryptedDnsError::InvalidEndpoint("DoH pipeline requires DoH endpoints".to_string()))
}
