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

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EchRejectedHandshake {
    pub public_name: Option<Vec<u8>>,
    pub retry_config_list: Option<Vec<u8>>,
}

impl EchRejectedHandshake {
    pub fn new(public_name: Option<Vec<u8>>, retry_config_list: Option<Vec<u8>>) -> Self {
        Self { public_name, retry_config_list }
    }
}

#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct EchRetryState {
    retry_attempted: bool,
}

impl EchRetryState {
    pub fn retry_attempted(&self) -> bool {
        self.retry_attempted
    }
}

pub trait EchPublicNameVerifier {
    fn verify_ech_public_name(&self, public_name: &str) -> bool;
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
    #[error("ECH retry config missing public name override")]
    MissingRetryPublicName,
    #[error("ECH retry config missing server retry configs")]
    MissingRetryConfig,
    #[error("ECH retry public name verification failed for {0}")]
    PublicNameVerificationFailed(String),
    #[error("ECH retry was already attempted")]
    RetryAlreadyAttempted,
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

pub fn prepare_ech_retry(
    rejected: &EchRejectedHandshake,
    verifier: &impl EchPublicNameVerifier,
    state: &mut EchRetryState,
) -> Result<EchSetup, EchFacadeError> {
    if state.retry_attempted {
        return Err(EchFacadeError::RetryAlreadyAttempted);
    }
    let public_name_bytes = rejected.public_name.as_deref().ok_or(EchFacadeError::MissingRetryPublicName)?;
    let public_name = std::str::from_utf8(public_name_bytes)
        .map_err(|_| EchFacadeError::PublicNameVerificationFailed(String::from("<invalid utf8>")))?;
    if !verifier.verify_ech_public_name(public_name) {
        return Err(EchFacadeError::PublicNameVerificationFailed(public_name.to_string()));
    }
    let retry_config_list = rejected.retry_config_list.clone().ok_or(EchFacadeError::MissingRetryConfig)?;
    let setup = EchSetup::Real(OutboundEchConfig::new(public_name, retry_config_list)?);
    state.retry_attempted = true;
    Ok(setup)
}

pub fn extract_boring_ech_rejection<S>(failed: &boring::ssl::MidHandshakeSslStream<S>) -> Option<EchRejectedHandshake> {
    let public_name = failed.ssl().get_ech_name_override().map(ToOwned::to_owned);
    let retry_config_list = failed.ssl().get_ech_retry_configs().map(ToOwned::to_owned);
    if public_name.is_none() && retry_config_list.is_none() {
        return None;
    }
    Some(EchRejectedHandshake::new(public_name, retry_config_list))
}

#[cfg(test)]
mod tests {
    use super::*;
    use golden_test_support::{assert_text_golden, canonicalize_json};
    use serde_json::json;
    use std::io::{Read, Write};
    use std::net::{Shutdown, SocketAddr, TcpListener, TcpStream};
    use std::thread;
    use std::thread::JoinHandle;
    use std::time::Duration;

    const BORING_ECH_CONFIG_LIST: &[u8] = &[
        0x00, 0x3e, 0xfe, 0x0d, 0x00, 0x3a, 0x00, 0x00, 0x20, 0x00, 0x20, 0xbb, 0x2f, 0x29, 0xe3, 0xe3, 0x05, 0x7e,
        0x04, 0x19, 0xd5, 0x2f, 0xc5, 0xf4, 0x41, 0x18, 0x77, 0x6f, 0x8d, 0xb6, 0x1c, 0xea, 0x4f, 0xdf, 0x76, 0x07,
        0x9b, 0x93, 0x60, 0x6c, 0x5a, 0x62, 0x48, 0x00, 0x08, 0x00, 0x01, 0x00, 0x01, 0x00, 0x01, 0x00, 0x03, 0x00,
        0x07, 0x65, 0x63, 0x68, 0x2e, 0x63, 0x6f, 0x6d, 0x00, 0x00,
    ];
    const BORING_ECH_CONFIG_LIST_2: &[u8] = &[
        0x00, 0x3e, 0xfe, 0x0d, 0x00, 0x3a, 0x01, 0x00, 0x20, 0x00, 0x20, 0x7e, 0xfb, 0x5f, 0xee, 0xa2, 0xa2, 0x74,
        0xb3, 0xff, 0xfe, 0x69, 0x51, 0x9e, 0xfa, 0xe1, 0xfa, 0x49, 0x8c, 0x61, 0x2c, 0xfa, 0xd0, 0x0f, 0x8c, 0x22,
        0x87, 0x0e, 0xbc, 0xb0, 0x2d, 0x76, 0x25, 0x00, 0x08, 0x00, 0x01, 0x00, 0x01, 0x00, 0x01, 0x00, 0x03, 0x00,
        0x07, 0x65, 0x63, 0x68, 0x2e, 0x63, 0x6f, 0x6d, 0x00, 0x00,
    ];
    const BORING_ECH_CONFIG_2: &[u8] = &[
        0xfe, 0x0d, 0x00, 0x3a, 0x01, 0x00, 0x20, 0x00, 0x20, 0x7e, 0xfb, 0x5f, 0xee, 0xa2, 0xa2, 0x74, 0xb3, 0xff,
        0xfe, 0x69, 0x51, 0x9e, 0xfa, 0xe1, 0xfa, 0x49, 0x8c, 0x61, 0x2c, 0xfa, 0xd0, 0x0f, 0x8c, 0x22, 0x87, 0x0e,
        0xbc, 0xb0, 0x2d, 0x76, 0x25, 0x00, 0x08, 0x00, 0x01, 0x00, 0x01, 0x00, 0x01, 0x00, 0x03, 0x00, 0x07, 0x65,
        0x63, 0x68, 0x2e, 0x63, 0x6f, 0x6d, 0x00, 0x00,
    ];
    const BORING_ECH_KEY_2: &[u8] = &[
        0xa7, 0x35, 0x44, 0x24, 0x6c, 0xfe, 0xb0, 0x53, 0x4c, 0x62, 0x7e, 0xca, 0x2e, 0x11, 0x8f, 0x56, 0x3c, 0xc0,
        0x2e, 0x6a, 0xa2, 0xe7, 0xaf, 0x3a, 0x7d, 0xb4, 0x72, 0x13, 0x1e, 0x88, 0xb6, 0x85,
    ];

    fn config() -> OutboundEchConfig {
        OutboundEchConfig::new("ech.com", BORING_ECH_CONFIG_LIST.to_vec()).expect("config")
    }

    fn config_2() -> OutboundEchConfig {
        OutboundEchConfig::new("ech.com", BORING_ECH_CONFIG_LIST_2.to_vec()).expect("config")
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

    #[test]
    fn outbound_facade_builds_single_retry_only_after_public_name_verification() {
        let rejected = EchRejectedHandshake::new(Some(b"ech.com".to_vec()), Some(BORING_ECH_CONFIG_LIST.to_vec()));
        let mut state = EchRetryState::default();
        let verifier = FakePublicNameVerifier::allow("ech.com");

        let retry = prepare_ech_retry(&rejected, &verifier, &mut state).expect("retry config");

        assert_eq!(retry, EchSetup::Real(config()));
        assert!(state.retry_attempted(), "retry configs are only safe for one follow-up connection");
        assert_eq!(verifier.verified_names(), vec!["ech.com".to_string()]);
        assert_eq!(
            prepare_ech_retry(&rejected, &verifier, &mut state).expect_err("single retry only"),
            EchFacadeError::RetryAlreadyAttempted
        );
    }

    #[test]
    fn outbound_facade_rejects_retry_configs_without_public_name_verification() {
        let rejected = EchRejectedHandshake::new(Some(b"ech.com".to_vec()), Some(BORING_ECH_CONFIG_LIST.to_vec()));
        let mut state = EchRetryState::default();
        let verifier = FakePublicNameVerifier::deny("ech.com");

        assert_eq!(
            prepare_ech_retry(&rejected, &verifier, &mut state).expect_err("public name verification required"),
            EchFacadeError::PublicNameVerificationFailed("ech.com".to_string())
        );
        assert!(!state.retry_attempted(), "do not consume retry budget until public_name verification passes");
    }

    #[test]
    fn boring_fixture_rejects_then_accepts_with_facade_retry_config() {
        let first = BoringEchServer::start(BORING_ECH_CONFIG_2, BORING_ECH_KEY_2);
        let initial_failure =
            connect_boring_ech(first.addr(), EchSetup::Real(config())).expect_err("stale ECH config is rejected");
        let boring::ssl::HandshakeError::Failure(failed) = initial_failure else {
            panic!("ECH rejection must be a Boring handshake failure");
        };
        let rejected = extract_boring_ech_rejection(&failed).expect("Boring exposes ECH rejection metadata");
        assert!(rejected.retry_config_list.is_some(), "Boring ECH rejection must expose retry configs");
        let mut state = EchRetryState::default();
        let retry_setup =
            prepare_ech_retry(&rejected, &FakePublicNameVerifier::allow("ech.com"), &mut state).expect("retry setup");

        assert_eq!(retry_setup, EchSetup::Real(config_2()));

        let second = BoringEchServer::start(BORING_ECH_CONFIG_2, BORING_ECH_KEY_2);
        let accepted = connect_boring_ech(second.addr(), retry_setup).expect("retry accepts fresh ECH config");

        assert!(accepted.ssl().ech_accepted(), "retry must negotiate real ECH, not clear TLS");
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

    #[derive(Clone)]
    struct FakePublicNameVerifier {
        expected: String,
        allow: bool,
        verified_names: std::sync::Arc<std::sync::Mutex<Vec<String>>>,
    }

    impl FakePublicNameVerifier {
        fn allow(expected: &str) -> Self {
            Self {
                expected: expected.to_string(),
                allow: true,
                verified_names: std::sync::Arc::new(std::sync::Mutex::new(Vec::new())),
            }
        }

        fn deny(expected: &str) -> Self {
            Self {
                expected: expected.to_string(),
                allow: false,
                verified_names: std::sync::Arc::new(std::sync::Mutex::new(Vec::new())),
            }
        }

        fn verified_names(&self) -> Vec<String> {
            self.verified_names.lock().expect("verified names lock").clone()
        }
    }

    impl EchPublicNameVerifier for FakePublicNameVerifier {
        fn verify_ech_public_name(&self, public_name: &str) -> bool {
            self.verified_names.lock().expect("verified names lock").push(public_name.to_string());
            self.allow && public_name == self.expected
        }
    }

    struct BoringEchServer {
        addr: SocketAddr,
        handle: Option<JoinHandle<()>>,
    }

    impl BoringEchServer {
        fn start(ech_config: &'static [u8], ech_key: &'static [u8]) -> Self {
            let listener = TcpListener::bind("127.0.0.1:0").expect("bind ECH fixture");
            let addr = listener.local_addr().expect("ECH fixture addr");
            let context = boring_ech_context(ech_config, ech_key);
            let handle = thread::spawn(move || {
                let (stream, _) = listener.accept().expect("accept ECH client");
                stream.set_read_timeout(Some(Duration::from_secs(5))).expect("server read timeout");
                stream.set_write_timeout(Some(Duration::from_secs(5))).expect("server write timeout");
                let ssl = boring::ssl::Ssl::new(&context).expect("server ssl");
                if let Ok(mut tls) = ssl.accept(stream) {
                    let _ = tls.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok");
                    let _ = tls.flush();
                }
            });
            Self { addr, handle: Some(handle) }
        }

        fn addr(&self) -> SocketAddr {
            self.addr
        }
    }

    impl Drop for BoringEchServer {
        fn drop(&mut self) {
            if let Some(handle) = self.handle.take() {
                handle.join().expect("ECH fixture join");
            }
        }
    }

    fn boring_ech_context(ech_config: &[u8], ech_key: &[u8]) -> boring::ssl::SslContext {
        let certificate = rcgen::generate_simple_self_signed(vec!["ech.com".to_string(), "foobar.com".to_string()])
            .expect("self-signed certificate");
        let cert = boring::x509::X509::from_der(certificate.cert.der().as_ref()).expect("x509 cert");
        let key = boring::pkey::PKey::private_key_from_der(&certificate.signing_key.serialize_der()).expect("key");
        let hpke = boring::hpke::HpkeKey::dhkem_p256_sha256(ech_key).expect("ECH HPKE key");
        let mut ech_keys = boring::ssl::SslEchKeys::builder().expect("ECH keys builder");
        ech_keys.add_key(true, ech_config, hpke).expect("ECH key");
        let mut context = boring::ssl::SslContext::builder(boring::ssl::SslMethod::tls()).expect("server context");
        context.set_certificate(&cert).expect("server cert");
        context.set_private_key(&key).expect("server key");
        context.set_ech_keys(&ech_keys.build()).expect("server ECH keys");
        context.build()
    }

    fn connect_boring_ech(
        addr: SocketAddr,
        setup: EchSetup,
    ) -> Result<boring::ssl::SslStream<TcpStream>, boring::ssl::HandshakeError<TcpStream>> {
        let mut connector =
            boring::ssl::SslConnector::builder(boring::ssl::SslMethod::tls()).expect("connector builder");
        connector.set_custom_verify_callback(boring::ssl::SslVerifyMode::PEER, |ssl| {
            let _ = ssl.get_ech_name_override();
            Ok(())
        });
        let connector = connector.build();
        let mut tls_config = connector.configure().expect("connect config");
        configure_ech(&mut tls_config, &setup).expect("configure ECH");
        let stream = TcpStream::connect(addr).expect("connect ECH fixture");
        stream.set_read_timeout(Some(Duration::from_secs(5))).expect("client read timeout");
        stream.set_write_timeout(Some(Duration::from_secs(5))).expect("client write timeout");
        tls_config.connect("foobar.com", stream)
    }
}
