use thiserror::Error;

/// Outbound Encrypted ClientHello configuration supplied by resolver/bootstrap policy.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OutboundEchConfig {
    pub public_name: String,
    pub config_list: Vec<u8>,
}

impl OutboundEchConfig {
    pub fn new(public_name: impl Into<String>, config_list: Vec<u8>) -> Result<Self, EchConfigError> {
        let public_name = public_name.into();
        if public_name.trim().is_empty() {
            return Err(EchConfigError::MissingPublicName);
        }
        if config_list.len() < 2 {
            return Err(EchConfigError::MalformedConfigList);
        }
        let declared_len = u16::from_be_bytes([config_list[0], config_list[1]]) as usize;
        if declared_len == 0 || declared_len + 2 != config_list.len() {
            return Err(EchConfigError::MalformedConfigList);
        }
        Ok(Self { public_name, config_list })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OutboundEchBackend {
    Rustls,
    Boring,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum EchLookupTransport {
    EncryptedDns,
    PlainDns,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct EchLookupRequest<'a> {
    pub inner_name: &'a str,
    pub transport: EchLookupTransport,
}

impl<'a> EchLookupRequest<'a> {
    pub const fn new(inner_name: &'a str, transport: EchLookupTransport) -> Self {
        Self { inner_name, transport }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct EchPolicy {
    pub enabled: bool,
    pub grease_when_unavailable: bool,
    pub backend_opt_out: bool,
}

impl Default for EchPolicy {
    fn default() -> Self {
        Self { enabled: true, grease_when_unavailable: true, backend_opt_out: false }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EchLookupOutcome {
    Available { public_name: String, config_list: Vec<u8> },
    NotPublished,
    ResolutionFailed(String),
}

pub trait OutboundEchResolver {
    fn resolve_https_ech_config_list(&self, request: &EchLookupRequest<'_>)
        -> Result<EchLookupOutcome, EchFacadeError>;
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum EchSetup {
    Real(OutboundEchConfig),
    Grease,
    OptedOut,
}

#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum EchConfigError {
    #[error("ECH public name is required")]
    MissingPublicName,
    #[error("ECHConfigList must be a non-empty length-prefixed vector")]
    MalformedConfigList,
}

#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum EchOutboundError {
    #[error("ECH config rejected by outbound TLS backend: {0}")]
    ConfigRejected(String),
    #[error("ECH retry required with server-provided retry config")]
    RetryRequired,
    #[error("ECH is not supported by {backend:?} outbound TLS backend")]
    UnsupportedBackend { backend: OutboundEchBackend },
}

#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum EchFacadeError {
    #[error("ECH HTTPS RR lookup must use encrypted DNS")]
    PlainDnsForbidden,
    #[error("ECH is disabled by policy")]
    Disabled,
    #[error("ECH config is invalid: {0}")]
    InvalidConfig(#[from] EchConfigError),
    #[error("ECH lookup failed and GREASE is disabled: {0}")]
    LookupFailed(String),
    #[error("ECH is not published and GREASE is disabled")]
    NotPublished,
    #[error("ECH backend configuration failed: {0}")]
    Backend(#[from] EchOutboundError),
}

pub fn require_ech_backend_support(
    backend: OutboundEchBackend,
    config: Option<&OutboundEchConfig>,
) -> Result<(), EchOutboundError> {
    if config.is_none() {
        return Ok(());
    }
    match backend {
        OutboundEchBackend::Rustls => Ok(()),
        OutboundEchBackend::Boring => Ok(()),
    }
}

pub fn resolve_outbound_ech(
    request: &EchLookupRequest<'_>,
    policy: EchPolicy,
    resolver: &impl OutboundEchResolver,
) -> Result<EchSetup, EchFacadeError> {
    if policy.backend_opt_out {
        return Ok(EchSetup::OptedOut);
    }
    if !policy.enabled {
        return Err(EchFacadeError::Disabled);
    }
    if request.transport != EchLookupTransport::EncryptedDns {
        return Err(EchFacadeError::PlainDnsForbidden);
    }

    match resolver.resolve_https_ech_config_list(request)? {
        EchLookupOutcome::Available { public_name, config_list } => {
            Ok(EchSetup::Real(OutboundEchConfig::new(public_name, config_list)?))
        }
        EchLookupOutcome::NotPublished if policy.grease_when_unavailable => Ok(EchSetup::Grease),
        EchLookupOutcome::NotPublished => Err(EchFacadeError::NotPublished),
        EchLookupOutcome::ResolutionFailed(_) if policy.grease_when_unavailable => Ok(EchSetup::Grease),
        EchLookupOutcome::ResolutionFailed(error) => Err(EchFacadeError::LookupFailed(error)),
    }
}

pub fn configure_boring_ech(
    config: &mut boring::ssl::ConnectConfiguration,
    ech_config: Option<&OutboundEchConfig>,
) -> Result<(), EchOutboundError> {
    if let Some(ech_config) = ech_config {
        config
            .set_ech_config_list(&ech_config.config_list)
            .map_err(|error| EchOutboundError::ConfigRejected(error.to_string()))?;
    }
    Ok(())
}

pub fn configure_ech(config: &mut boring::ssl::ConnectConfiguration, setup: &EchSetup) -> Result<(), EchFacadeError> {
    match setup {
        EchSetup::Real(ech_config) => configure_boring_ech(config, Some(ech_config))?,
        EchSetup::Grease => config.set_enable_ech_grease(true),
        EchSetup::OptedOut => {}
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use golden_test_support::{assert_text_golden, canonicalize_json};
    use serde_json::json;
    use std::io::Read;
    use std::net::{Shutdown, TcpListener, TcpStream};
    use std::thread;
    use std::time::Duration;

    const BORING_ECH_CONFIG_LIST: &[u8] = &[
        0x00, 0x3e, 0xfe, 0x0d, 0x00, 0x3a, 0x00, 0x00, 0x20, 0x00, 0x20, 0xbb, 0x2f, 0x29, 0xe3, 0xe3, 0x05, 0x7e,
        0x04, 0x19, 0xd5, 0x2f, 0xc5, 0xf4, 0x41, 0x18, 0x77, 0x6f, 0x8d, 0xb6, 0x1c, 0xea, 0x4f, 0xdf, 0x76, 0x07,
        0x9b, 0x93, 0x60, 0x6c, 0x5a, 0x62, 0x48, 0x00, 0x08, 0x00, 0x01, 0x00, 0x01, 0x00, 0x01, 0x00, 0x03, 0x00,
        0x07, 0x65, 0x63, 0x68, 0x2e, 0x63, 0x6f, 0x6d, 0x00, 0x00,
    ];

    fn config() -> OutboundEchConfig {
        OutboundEchConfig::new("ech.com", BORING_ECH_CONFIG_LIST.to_vec()).expect("config")
    }

    #[test]
    fn ech_config_list_must_be_length_prefixed() {
        assert_eq!(
            OutboundEchConfig::new("public.example", vec![0, 4, 1, 2, 3]).expect_err("length mismatch"),
            EchConfigError::MalformedConfigList
        );
        assert_eq!(
            OutboundEchConfig::new("", vec![0, 3, 1, 2, 3]).expect_err("missing public name"),
            EchConfigError::MissingPublicName
        );
    }

    #[test]
    fn boring_backend_accepts_configured_ech_contract() {
        require_ech_backend_support(OutboundEchBackend::Boring, Some(&config())).expect("boring contract");
    }

    #[test]
    fn rustls_backend_accepts_configured_ech_contract() {
        require_ech_backend_support(OutboundEchBackend::Rustls, Some(&config())).expect("rustls contract");
    }

    #[test]
    fn boring_ech_config_is_applied_to_connect_configuration() {
        let connector = crate::build_connector("native_default", true).expect("connector");
        let mut tls_config = connector.configure().expect("connect config");
        configure_boring_ech(&mut tls_config, Some(&config())).expect("configured");
    }

    #[test]
    fn outbound_facade_refuses_plain_dns_https_rr_lookup_for_ech() {
        let resolver = FakeEchResolver::available("public.example", BORING_ECH_CONFIG_LIST);
        let request = EchLookupRequest::new("inner.example", EchLookupTransport::PlainDns);

        assert_eq!(
            resolve_outbound_ech(&request, EchPolicy::default(), &resolver).expect_err("plain DNS is forbidden"),
            EchFacadeError::PlainDnsForbidden
        );
        assert_eq!(resolver.calls(), 0, "plain DNS must be rejected before resolver I/O");
    }

    #[test]
    fn outbound_facade_uses_encrypted_dns_ech_config_or_grease() {
        let real = FakeEchResolver::available("public.example", BORING_ECH_CONFIG_LIST);
        let real_request = EchLookupRequest::new("inner.example", EchLookupTransport::EncryptedDns);

        assert_eq!(
            resolve_outbound_ech(&real_request, EchPolicy::default(), &real).expect("real ECH config"),
            EchSetup::Real(OutboundEchConfig::new("public.example", BORING_ECH_CONFIG_LIST.to_vec()).expect("config"))
        );
        assert_eq!(real.calls(), 1);

        let unpublished = FakeEchResolver::not_published();
        let grease = resolve_outbound_ech(&real_request, EchPolicy::default(), &unpublished).expect("GREASE fallback");

        assert_eq!(grease, EchSetup::Grease);
        assert_eq!(unpublished.calls(), 1);
    }

    #[test]
    fn outbound_facade_decisions_match_golden() {
        let request = EchLookupRequest::new("inner.example", EchLookupTransport::EncryptedDns);
        let cases = [
            ("encrypted-real", FakeEchResolver::available("public.example", BORING_ECH_CONFIG_LIST)),
            ("encrypted-unpublished", FakeEchResolver::not_published()),
        ];

        let actual = cases
            .into_iter()
            .map(|(name, resolver)| {
                let setup = resolve_outbound_ech(&request, EchPolicy::default(), &resolver).expect(name);
                match setup {
                    EchSetup::Real(config) => json!({
                        "name": name,
                        "setup": "real",
                        "publicName": config.public_name,
                        "configListLen": config.config_list.len(),
                    }),
                    EchSetup::Grease => json!({
                        "name": name,
                        "setup": "grease",
                    }),
                    EchSetup::OptedOut => json!({
                        "name": name,
                        "setup": "opted_out",
                    }),
                }
            })
            .collect::<Vec<_>>();
        let actual = canonicalize_json(&serde_json::to_string_pretty(&actual).expect("serialize ECH decisions"))
            .expect("canonicalize ECH decisions");

        assert_text_golden(env!("CARGO_MANIFEST_DIR"), "tests/golden/outbound_ech_facade_decisions.json", &actual);
    }

    #[test]
    fn configure_ech_enables_grease_when_no_real_config_is_available() {
        let payload = capture_client_hello_with_ech_setup(EchSetup::Grease);
        let layout = ripdpi_packets::parse_tls_client_hello_layout(&payload).expect("parse ClientHello");

        assert!(
            layout.extensions.iter().any(|extension| extension.ext_type == 0xfe0d),
            "GREASE ECH must add the encrypted_client_hello extension"
        );
    }

    fn capture_client_hello_with_ech_setup(setup: EchSetup) -> Vec<u8> {
        let listener = TcpListener::bind("127.0.0.1:0").expect("bind loopback listener");
        let addr = listener.local_addr().expect("listener addr");
        let server = thread::spawn(move || {
            let (mut socket, _) = listener.accept().expect("accept client");
            socket.set_read_timeout(Some(Duration::from_secs(5))).expect("set server read timeout");
            let mut header = [0_u8; 5];
            socket.read_exact(&mut header).expect("read TLS record header");
            let payload_len = u16::from_be_bytes([header[3], header[4]]) as usize;
            let mut payload = vec![0_u8; payload_len];
            socket.read_exact(&mut payload).expect("read TLS record payload");
            let _ = socket.shutdown(Shutdown::Both);
            [header.to_vec(), payload].concat()
        });

        let connector = crate::build_connector("native_default", false).expect("connector");
        let mut tls_config = connector.configure().expect("connect config");
        configure_ech(&mut tls_config, &setup).expect("configure ECH");
        let stream = TcpStream::connect(addr).expect("connect loopback socket");
        stream.set_read_timeout(Some(Duration::from_secs(5))).expect("set client read timeout");
        stream.set_write_timeout(Some(Duration::from_secs(5))).expect("set client write timeout");
        let _ = tls_config.connect("inner.example", stream);
        server.join().expect("server join")
    }

    #[derive(Clone)]
    struct FakeEchResolver {
        outcome: EchLookupOutcome,
        calls: std::sync::Arc<std::sync::atomic::AtomicUsize>,
    }

    impl FakeEchResolver {
        fn available(public_name: &str, config_list: &[u8]) -> Self {
            Self {
                outcome: EchLookupOutcome::Available {
                    public_name: public_name.to_string(),
                    config_list: config_list.to_vec(),
                },
                calls: std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0)),
            }
        }

        fn not_published() -> Self {
            Self {
                outcome: EchLookupOutcome::NotPublished,
                calls: std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0)),
            }
        }

        fn calls(&self) -> usize {
            self.calls.load(std::sync::atomic::Ordering::SeqCst)
        }
    }

    impl OutboundEchResolver for FakeEchResolver {
        fn resolve_https_ech_config_list(
            &self,
            _request: &EchLookupRequest<'_>,
        ) -> Result<EchLookupOutcome, EchFacadeError> {
            self.calls.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
            Ok(self.outcome.clone())
        }
    }
}
