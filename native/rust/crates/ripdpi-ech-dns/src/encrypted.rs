use std::time::Duration;

use ripdpi_dns_resolver::{
    EncryptedDnsConnectHooks, EncryptedDnsEndpoint, EncryptedDnsResolver, EncryptedDnsSocks5Credentials,
    EncryptedDnsTransport, HttpsRr, HttpsServiceSelection, extract_ip_answers, parse_ech_config_list,
    parse_https_service_bindings, select_https_service_bindings,
};

use crate::transport::TransportConfig;
use crate::util::{bounded_scan_io_timeout, now_ms};

use super::wire::{DNS_RECORD_TYPE_A, DNS_RECORD_TYPE_HTTPS, DNS_RECORD_TYPE_SVCB, build_dns_query_with_type};

#[derive(Clone, Debug)]
pub enum EchResolutionOutcome {
    /// DoH succeeded and HTTPS record contained an EchConfigList.
    Available(Vec<u8>),
    /// DoH succeeded but the HTTPS response had no EchConfigList parameter.
    NotPublished,
    /// DoH query itself failed (network error, timeout, blocked, etc.).
    ResolutionFailed(String),
}

pub struct EncryptedDnsEchResolver {
    resolver_id: &'static str,
    connect_hooks: EncryptedDnsConnectHooks,
}

impl EncryptedDnsEchResolver {
    pub fn adguard(connect_hooks: EncryptedDnsConnectHooks) -> Self {
        Self { resolver_id: "adguard", connect_hooks }
    }
}

impl ripdpi_tls_profiles::OutboundEchResolver for EncryptedDnsEchResolver {
    fn resolve_https_ech_config_list(
        &self,
        request: &ripdpi_tls_profiles::EchLookupRequest<'_>,
    ) -> Result<ripdpi_tls_profiles::EchLookupOutcome, ripdpi_tls_profiles::EchFacadeError> {
        // Only the one resolver: the inner name is not sent to other third-party resolvers.
        let endpoint = super::encrypted_dns_endpoint_for_resolver_id(self.resolver_id);
        let transport = TransportConfig::Direct { route_experiment: None };
        ech_lookup_outcome(resolve_https_ech_configs_via_encrypted_dns_with_hooks(
            request.inner_name,
            endpoint,
            &transport,
            self.connect_hooks.clone(),
        ))
    }
}

/// An HTTPS-record lookup already done by the caller (see
/// [`resolve_outbound_ech_config_via_encrypted_dns_async`]).
struct ResolvedEchLookup(EchResolutionOutcome);

impl ripdpi_tls_profiles::OutboundEchResolver for ResolvedEchLookup {
    fn resolve_https_ech_config_list(
        &self,
        _request: &ripdpi_tls_profiles::EchLookupRequest<'_>,
    ) -> Result<ripdpi_tls_profiles::EchLookupOutcome, ripdpi_tls_profiles::EchFacadeError> {
        ech_lookup_outcome(self.0.clone())
    }
}

fn ech_lookup_outcome(
    outcome: EchResolutionOutcome,
) -> Result<ripdpi_tls_profiles::EchLookupOutcome, ripdpi_tls_profiles::EchFacadeError> {
    match outcome {
        EchResolutionOutcome::Available(config_list) => {
            let public_name = ech_public_name(&config_list)
                .ok_or_else(|| ripdpi_tls_profiles::EchFacadeError::LookupFailed("ECH public_name missing".into()))?;
            Ok(ripdpi_tls_profiles::EchLookupOutcome::Available { public_name, config_list })
        }
        EchResolutionOutcome::NotPublished => Ok(ripdpi_tls_profiles::EchLookupOutcome::NotPublished),
        EchResolutionOutcome::ResolutionFailed(error) => {
            Ok(ripdpi_tls_profiles::EchLookupOutcome::ResolutionFailed(error))
        }
    }
}

fn outbound_ech_config(
    inner_name: &str,
    resolver: &impl ripdpi_tls_profiles::OutboundEchResolver,
) -> Result<Option<ripdpi_tls_profiles::OutboundEchConfig>, ripdpi_tls_profiles::EchFacadeError> {
    let request =
        ripdpi_tls_profiles::EchLookupRequest::new(inner_name, ripdpi_tls_profiles::EchLookupTransport::EncryptedDns);
    match ripdpi_tls_profiles::resolve_outbound_ech(&request, ripdpi_tls_profiles::EchPolicy::default(), resolver)? {
        ripdpi_tls_profiles::EchSetup::Real(config) => Ok(Some(config)),
        ripdpi_tls_profiles::EchSetup::Grease | ripdpi_tls_profiles::EchSetup::OptedOut => Ok(None),
    }
}

/// Blocking ECH bootstrap lookup. It must not run on a thread inside a Tokio
/// runtime: the blocking exchange then fails at once and the lookup degrades
/// to GREASE. Async callers use
/// [`resolve_outbound_ech_config_via_encrypted_dns_async`].
pub fn resolve_outbound_ech_config_via_encrypted_dns(
    inner_name: &str,
    connect_hooks: EncryptedDnsConnectHooks,
) -> Result<Option<ripdpi_tls_profiles::OutboundEchConfig>, ripdpi_tls_profiles::EchFacadeError> {
    outbound_ech_config(inner_name, &EncryptedDnsEchResolver::adguard(connect_hooks))
}

/// Async ECH bootstrap lookup through the same resolver and policy as
/// [`resolve_outbound_ech_config_via_encrypted_dns`], for callers that run
/// inside a Tokio runtime.
///
/// # Cancel safety
///
/// Cancel-safe: it owns its resolver and query; dropping it drops the
/// in-flight exchange and publishes nothing.
pub async fn resolve_outbound_ech_config_via_encrypted_dns_async(
    inner_name: &str,
    connect_hooks: EncryptedDnsConnectHooks,
) -> Result<Option<ripdpi_tls_profiles::OutboundEchConfig>, ripdpi_tls_profiles::EchFacadeError> {
    let endpoint = super::encrypted_dns_endpoint_for_resolver_id("adguard");
    let transport = TransportConfig::Direct { route_experiment: None };
    let outcome = lookup_https_ech_async(inner_name, endpoint, &transport, connect_hooks).await;
    outbound_ech_config(inner_name, &ResolvedEchLookup(outcome))
}

/// The async twin of [`follow_https_aliases`] for one resolver.
///
/// # Cancel safety
///
/// Cancel-safe: each hop owns its resolver and query; dropping the future
/// drops the in-flight exchange and publishes nothing.
async fn lookup_https_ech_async(
    domain: &str,
    endpoint: EncryptedDnsEndpoint,
    transport: &TransportConfig,
    connect_hooks: EncryptedDnsConnectHooks,
) -> EchResolutionOutcome {
    let mut name = domain.to_string();
    for _ in 0..=MAX_HTTPS_ALIAS_HOPS {
        let response =
            match prepare_exchange(&name, DNS_RECORD_TYPE_HTTPS, endpoint.clone(), transport, connect_hooks.clone()) {
                Err(error) => return EchResolutionOutcome::ResolutionFailed(error),
                Ok((resolver, packet)) => match resolver.exchange(&packet).await {
                    Err(error) => return EchResolutionOutcome::ResolutionFailed(error.to_string()),
                    Ok(response) => response,
                },
            };
        match https_response_ech_step(&response) {
            EchStep::Done(outcome) => return outcome,
            EchStep::Alias(target) => name = target,
        }
    }
    alias_chain_too_long()
}

pub fn resolve_via_encrypted_dns(
    domain: &str,
    endpoint: EncryptedDnsEndpoint,
    transport: &TransportConfig,
) -> Result<Vec<String>, String> {
    let (result, _raw) = resolve_via_encrypted_dns_with_raw(domain, endpoint, transport);
    result
}

/// Like [`resolve_via_encrypted_dns`] but also returns the raw response bytes
/// for record-level comparison with the UDP response.
pub fn resolve_via_encrypted_dns_with_raw(
    domain: &str,
    endpoint: EncryptedDnsEndpoint,
    transport: &TransportConfig,
) -> (Result<Vec<String>, String>, Option<Vec<u8>>) {
    match exchange_encrypted_dns_query(domain, DNS_RECORD_TYPE_A, endpoint, transport) {
        Ok(raw) => {
            let parsed = extract_ip_answers(&raw).map_err(|err| err.to_string());
            (parsed, Some(raw))
        }
        Err(err) => (Err(err), None),
    }
}

pub fn resolve_https_ech_configs_via_encrypted_dns_with_endpoint(
    domain: &str,
    endpoint: EncryptedDnsEndpoint,
    transport: &TransportConfig,
) -> EchResolutionOutcome {
    resolve_https_ech_configs_via_encrypted_dns_with_hooks(domain, endpoint, transport, EncryptedDnsConnectHooks::new())
}

/// Preserve the caller-owned socket policy for the ECH bootstrap lookup.
fn resolve_https_ech_configs_via_encrypted_dns_with_hooks(
    domain: &str,
    endpoint: EncryptedDnsEndpoint,
    transport: &TransportConfig,
    connect_hooks: EncryptedDnsConnectHooks,
) -> EchResolutionOutcome {
    follow_https_aliases(domain, |name| {
        exchange_encrypted_dns_query_with_hooks(
            name,
            DNS_RECORD_TYPE_HTTPS,
            endpoint.clone(),
            transport,
            connect_hooks.clone(),
        )
    })
}

/// Queries `domain` for HTTPS records through `exchange`, following AliasMode
/// TargetNames up to [`MAX_HTTPS_ALIAS_HOPS`] times.
fn follow_https_aliases(
    domain: &str,
    mut exchange: impl FnMut(&str) -> Result<Vec<u8>, String>,
) -> EchResolutionOutcome {
    let mut name = domain.to_string();
    for _ in 0..=MAX_HTTPS_ALIAS_HOPS {
        let response = match exchange(&name) {
            Err(err) => return EchResolutionOutcome::ResolutionFailed(err),
            Ok(response) => response,
        };
        match https_response_ech_step(&response) {
            EchStep::Done(outcome) => return outcome,
            EchStep::Alias(target) => name = target,
        }
    }
    alias_chain_too_long()
}

/// AliasMode records followed for one ECH lookup (RFC 9460 3 asks for a bound).
const MAX_HTTPS_ALIAS_HOPS: usize = 3;

/// One HTTPS answer: a final outcome, or the AliasMode TargetName to query next.
enum EchStep {
    Done(EchResolutionOutcome),
    Alias(String),
}

fn https_response_ech_step(response: &[u8]) -> EchStep {
    match select_https_service_bindings(response) {
        Err(error) => EchStep::Done(EchResolutionOutcome::ResolutionFailed(error.to_string())),
        Ok(HttpsServiceSelection::Alias(target)) => EchStep::Alias(target.trim_end_matches('.').to_string()),
        Ok(HttpsServiceSelection::Service(bindings)) => {
            EchStep::Done(match bindings.into_iter().find_map(|binding| binding.ech_config) {
                Some(config) => EchResolutionOutcome::Available(config.raw_list_bytes),
                None => EchResolutionOutcome::NotPublished,
            })
        }
    }
}

fn alias_chain_too_long() -> EchResolutionOutcome {
    EchResolutionOutcome::ResolutionFailed("HTTPS AliasMode chain is too long".to_string())
}

pub fn resolve_https_service_bindings_via_encrypted_dns_with_endpoint(
    domain: &str,
    endpoint: EncryptedDnsEndpoint,
    transport: &TransportConfig,
) -> Result<Vec<HttpsRr>, String> {
    let mut bindings = Vec::new();
    for record_type in [DNS_RECORD_TYPE_HTTPS, DNS_RECORD_TYPE_SVCB] {
        let response = exchange_encrypted_dns_query(domain, record_type, endpoint.clone(), transport)?;
        bindings.extend(parse_https_service_bindings(&response).map_err(|error| error.to_string())?);
    }
    Ok(bindings)
}

pub fn exchange_encrypted_dns_query(
    domain: &str,
    record_type: u16,
    endpoint: EncryptedDnsEndpoint,
    transport: &TransportConfig,
) -> Result<Vec<u8>, String> {
    exchange_encrypted_dns_query_with_hooks(domain, record_type, endpoint, transport, EncryptedDnsConnectHooks::new())
}

fn exchange_encrypted_dns_query_with_hooks(
    domain: &str,
    record_type: u16,
    endpoint: EncryptedDnsEndpoint,
    transport: &TransportConfig,
    connect_hooks: EncryptedDnsConnectHooks,
) -> Result<Vec<u8>, String> {
    exchange_encrypted_dns_query_with_request(domain, record_type, endpoint, transport, connect_hooks)
        .map(|(_, response)| response)
}

/// Return the exact transmitted query so diagnostic parsing can bind negative replies.
///
/// Blocking: it must not run on a thread inside a Tokio runtime, where the
/// blocking exchange fails at once.
pub fn exchange_encrypted_dns_query_with_request(
    domain: &str,
    record_type: u16,
    endpoint: EncryptedDnsEndpoint,
    transport: &TransportConfig,
    connect_hooks: EncryptedDnsConnectHooks,
) -> Result<(Vec<u8>, Vec<u8>), String> {
    let (resolver, packet) = prepare_exchange(domain, record_type, endpoint, transport, connect_hooks)?;
    let response = resolver.exchange_blocking(&packet).map_err(|err| err.to_string())?;
    Ok((packet, response))
}

/// Build the resolver and the DNS query for one exchange.
fn prepare_exchange(
    domain: &str,
    record_type: u16,
    endpoint: EncryptedDnsEndpoint,
    transport: &TransportConfig,
    connect_hooks: EncryptedDnsConnectHooks,
) -> Result<(EncryptedDnsResolver, Vec<u8>), String> {
    let transport = match transport {
        TransportConfig::Direct { .. } => EncryptedDnsTransport::Direct,
        TransportConfig::Socks5 { host, port, credentials } => {
            let credentials = credentials.as_ref().map(|credentials| EncryptedDnsSocks5Credentials {
                username: credentials.username().to_string(),
                password: credentials.password().to_string(),
            });
            EncryptedDnsTransport::Socks5 { host: host.clone(), port: *port, credentials }
        }
    };
    let timeout = bounded_scan_io_timeout(Duration::from_secs(4)).map_err(str::to_string)?;
    let resolver = EncryptedDnsResolver::with_timeout_and_connect_hooks(
        endpoint,
        transport,
        timeout,
        encrypted_dns_connect_hooks(connect_hooks),
    )
    .map_err(|err| err.to_string())?;
    let query_id = ((now_ms() & 0xffff) as u16).max(1);
    let packet = build_dns_query_with_type(domain, query_id, record_type)?;
    Ok((resolver, packet))
}

/// Add the DoT TLS profile without replacing caller-owned socket hooks.
fn encrypted_dns_connect_hooks(hooks: EncryptedDnsConnectHooks) -> EncryptedDnsConnectHooks {
    hooks.with_dot_tls_connector_builder(|| {
        ripdpi_tls_profiles::configure_builder("chrome_stable").map_err(|error| error.to_string())
    })
}

/// The ECHConfigList of the highest-priority ServiceMode binding for the query
/// name. An AliasMode answer yields `None`: this helper does not query again.
pub fn extract_ech_config_list_from_https_response(packet: &[u8]) -> Result<Option<Vec<u8>>, String> {
    match https_response_ech_step(packet) {
        EchStep::Done(EchResolutionOutcome::Available(config_list)) => Ok(Some(config_list)),
        EchStep::Done(EchResolutionOutcome::ResolutionFailed(error)) => Err(error),
        EchStep::Done(EchResolutionOutcome::NotPublished) | EchStep::Alias(_) => Ok(None),
    }
}

pub fn ech_public_name(config_list: &[u8]) -> Option<String> {
    parse_ech_config_list(config_list).ok()?.configs.into_iter().find_map(|config| config.public_name)
}

#[cfg(test)]
mod tests {
    use std::io::{Read, Write};
    use std::net::TcpListener;
    use std::thread;

    use ripdpi_dns_resolver::EncryptedDnsProtocol;

    use super::*;

    const BORING_ECH_CONFIG_LIST: &[u8] = &[
        0x00, 0x3e, 0xfe, 0x0d, 0x00, 0x3a, 0x00, 0x00, 0x20, 0x00, 0x20, 0xbb, 0x2f, 0x29, 0xe3, 0xe3, 0x05, 0x7e,
        0x04, 0x19, 0xd5, 0x2f, 0xc5, 0xf4, 0x41, 0x18, 0x77, 0x6f, 0x8d, 0xb6, 0x1c, 0xea, 0x4f, 0xdf, 0x76, 0x07,
        0x9b, 0x93, 0x60, 0x6c, 0x5a, 0x62, 0x48, 0x00, 0x08, 0x00, 0x01, 0x00, 0x01, 0x00, 0x01, 0x00, 0x03, 0x00,
        0x07, 0x65, 0x63, 0x68, 0x2e, 0x63, 0x6f, 0x6d, 0x00, 0x00,
    ];

    #[test]
    fn encrypted_dns_connect_hooks_install_dot_tls_builder() {
        let hooks = encrypted_dns_connect_hooks(EncryptedDnsConnectHooks::new());

        assert!(hooks.dot_tls_connector_builder.is_some());
        assert!(hooks.direct_tcp_connector.is_none());
        assert!(hooks.direct_udp_binder.is_none());
    }

    #[test]
    fn encrypted_dns_connect_hooks_preserve_required_socket_hooks() {
        let hooks = encrypted_dns_connect_hooks(
            EncryptedDnsConnectHooks::new()
                .require_direct_tcp_connector()
                .require_direct_udp_binder()
                .with_direct_tcp_connector(|_, _| async {
                    Err::<std::net::TcpStream, _>(std::io::Error::other("denied"))
                })
                .with_direct_udp_binder(|_| Err(std::io::Error::other("denied"))),
        );

        assert!(hooks.dot_tls_connector_builder.is_some());
        assert!(hooks.direct_tcp_connector.is_some());
        assert!(hooks.direct_udp_binder.is_some());
    }

    fn encode_name(name: &str, out: &mut Vec<u8>) {
        for label in name.trim_end_matches('.').split('.').filter(|label| !label.is_empty()) {
            out.push(u8::try_from(label.len()).expect("label fits"));
            out.extend(label.as_bytes());
        }
        out.push(0);
    }

    /// An HTTPS answer for `name` with (priority, target, ECHConfigList) records.
    fn https_answer(name: &str, records: &[(u16, &str, Option<Vec<u8>>)]) -> Vec<u8> {
        let mut packet = vec![0x12, 0x34, 0x81, 0x80, 0, 1];
        packet.extend(u16::try_from(records.len()).expect("count fits").to_be_bytes());
        packet.extend([0, 0, 0, 0]);
        encode_name(name, &mut packet);
        packet.extend(DNS_RECORD_TYPE_HTTPS.to_be_bytes());
        packet.extend(1u16.to_be_bytes());
        for (priority, target, ech) in records {
            let mut rdata = priority.to_be_bytes().to_vec();
            encode_name(target, &mut rdata);
            if let Some(ech) = ech {
                rdata.extend(5u16.to_be_bytes());
                rdata.extend(u16::try_from(ech.len()).expect("ECH fits").to_be_bytes());
                rdata.extend(ech);
            }
            encode_name(name, &mut packet);
            packet.extend(DNS_RECORD_TYPE_HTTPS.to_be_bytes());
            packet.extend(1u16.to_be_bytes());
            packet.extend(300u32.to_be_bytes());
            packet.extend(u16::try_from(rdata.len()).expect("rdata fits").to_be_bytes());
            packet.extend(rdata);
        }
        packet
    }

    /// The fixture ECHConfigList with the public name `ech.org` instead of `ech.com`.
    fn other_ech_config_list() -> Vec<u8> {
        let mut list = BORING_ECH_CONFIG_LIST.to_vec();
        let end = list.len() - 2;
        list[end - 3..end].copy_from_slice(b"org");
        list
    }

    #[test]
    fn https_ech_lookup_uses_the_lowest_svc_priority() {
        let response = https_answer(
            "relay.example",
            &[(2, ".", Some(other_ech_config_list())), (1, ".", Some(BORING_ECH_CONFIG_LIST.to_vec()))],
        );

        let config_list = extract_ech_config_list_from_https_response(&response).expect("answer parses");

        assert_eq!(config_list.as_deref(), Some(BORING_ECH_CONFIG_LIST));
    }

    #[test]
    fn https_ech_lookup_follows_alias_mode() {
        let mut queried = Vec::new();
        let outcome = follow_https_aliases("relay.example", |name| {
            queried.push(name.to_string());
            Ok(match name {
                "relay.example" => https_answer(name, &[(0, "svc.example.net.", None)]),
                _ => https_answer(name, &[(1, ".", Some(BORING_ECH_CONFIG_LIST.to_vec()))]),
            })
        });

        assert!(matches!(outcome, EchResolutionOutcome::Available(list) if list == BORING_ECH_CONFIG_LIST));
        assert_eq!(queried, ["relay.example", "svc.example.net"]);
    }

    #[test]
    fn https_ech_lookup_bounds_the_alias_chain() {
        let mut queries = 0;
        let outcome = follow_https_aliases("relay.example", |name| {
            queries += 1;
            Ok(https_answer(name, &[(0, "relay.example.", None)]))
        });

        assert!(matches!(outcome, EchResolutionOutcome::ResolutionFailed(_)));
        assert_eq!(queries, MAX_HTTPS_ALIAS_HOPS + 1);
    }

    #[test]
    fn ech_bootstrap_sends_the_name_only_to_the_selected_resolver() {
        use std::sync::{Arc, Mutex};

        let dialed = Arc::new(Mutex::new(Vec::new()));
        let record = dialed.clone();
        let hooks = EncryptedDnsConnectHooks::new().with_direct_tcp_connector(move |target, _| {
            record.lock().expect("dialed lock").push(target.ip());
            async { Err::<std::net::TcpStream, _>(std::io::Error::other("blocked")) }
        });
        let request = ripdpi_tls_profiles::EchLookupRequest::new(
            "relay.example",
            ripdpi_tls_profiles::EchLookupTransport::EncryptedDns,
        );

        let outcome = ripdpi_tls_profiles::OutboundEchResolver::resolve_https_ech_config_list(
            &EncryptedDnsEchResolver::adguard(hooks),
            &request,
        )
        .expect("lookup outcome");

        assert!(matches!(outcome, ripdpi_tls_profiles::EchLookupOutcome::ResolutionFailed(_)));
        assert_eq!(*dialed.lock().expect("dialed lock"), crate::bootstrap_ips_for_resolver("adguard"));
    }

    #[test]
    fn encrypted_ech_resolver_reuses_ech_config_list_parser_for_public_name() {
        assert_eq!(ech_public_name(BORING_ECH_CONFIG_LIST).as_deref(), Some("ech.com"));
    }

    #[test]
    fn encrypted_dns_query_preserves_authenticated_socks_route() {
        const USERNAME: &str = "ech-fixture";
        const PASSWORD: &str = "ech-secret";
        let listener = TcpListener::bind(("127.0.0.1", 0)).expect("bind SOCKS fixture");
        let proxy_addr = listener.local_addr().expect("SOCKS fixture address");
        let server = thread::spawn(move || {
            let (mut stream, _) = listener.accept().expect("accept encrypted DNS client");
            let mut greeting = [0u8; 2];
            stream.read_exact(&mut greeting).expect("read SOCKS greeting");
            assert_eq!(greeting[0], 5);
            let mut methods = vec![0u8; usize::from(greeting[1])];
            stream.read_exact(&mut methods).expect("read SOCKS methods");
            assert!(methods.contains(&2), "encrypted DNS must advertise username/password authentication");
            stream.write_all(&[5, 2]).expect("select SOCKS username/password authentication");

            let mut auth_header = [0u8; 2];
            stream.read_exact(&mut auth_header).expect("read SOCKS auth header");
            assert_eq!(auth_header[0], 1);
            let mut username = vec![0u8; usize::from(auth_header[1])];
            stream.read_exact(&mut username).expect("read SOCKS username");
            let mut password_len = [0u8; 1];
            stream.read_exact(&mut password_len).expect("read SOCKS password length");
            let mut password = vec![0u8; usize::from(password_len[0])];
            stream.read_exact(&mut password).expect("read SOCKS password");
            assert_eq!(username, USERNAME.as_bytes());
            assert_eq!(password, PASSWORD.as_bytes());
        });
        let endpoint = EncryptedDnsEndpoint {
            protocol: EncryptedDnsProtocol::Dot,
            resolver_id: Some("fixture".to_string()),
            host: "resolver.fixture".to_string(),
            port: 853,
            tls_server_name: Some("resolver.fixture".to_string()),
            bootstrap_ips: Vec::new(),
            doh_url: None,
            dnscrypt_provider_name: None,
            dnscrypt_public_key: None,
            odoh: None,
        };
        let transport = TransportConfig::Socks5 {
            host: proxy_addr.ip().to_string(),
            port: proxy_addr.port(),
            credentials: ripdpi_diagnostics_transport::transport::Socks5Credentials::new(USERNAME, PASSWORD),
        };

        let _ = exchange_encrypted_dns_query("example.com", DNS_RECORD_TYPE_A, endpoint, &transport);

        server.join().expect("SOCKS fixture must observe encrypted DNS credentials");
    }
}
