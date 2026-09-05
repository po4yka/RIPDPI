use std::time::Duration;

use ripdpi_dns_resolver::{
    EncryptedDnsConnectHooks, EncryptedDnsEndpoint, EncryptedDnsResolver, EncryptedDnsSocks5Credentials,
    EncryptedDnsTransport, HttpsRr, extract_ip_answers, parse_ech_config_list, parse_https_service_bindings,
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
        let endpoint = super::encrypted_dns_endpoint_for_resolver_id(self.resolver_id);
        let transport = TransportConfig::Direct { route_experiment: None };
        match resolve_https_ech_configs_via_encrypted_dns_with_hooks(
            request.inner_name,
            endpoint,
            &transport,
            self.connect_hooks.clone(),
        ) {
            EchResolutionOutcome::Available(config_list) => {
                let public_name = ech_public_name(&config_list).ok_or_else(|| {
                    ripdpi_tls_profiles::EchFacadeError::LookupFailed("ECH public_name missing".into())
                })?;
                Ok(ripdpi_tls_profiles::EchLookupOutcome::Available { public_name, config_list })
            }
            EchResolutionOutcome::NotPublished => Ok(ripdpi_tls_profiles::EchLookupOutcome::NotPublished),
            EchResolutionOutcome::ResolutionFailed(error) => {
                Ok(ripdpi_tls_profiles::EchLookupOutcome::ResolutionFailed(error))
            }
        }
    }
}

pub fn resolve_outbound_ech_config_via_encrypted_dns(
    inner_name: &str,
    connect_hooks: EncryptedDnsConnectHooks,
) -> Result<Option<ripdpi_tls_profiles::OutboundEchConfig>, ripdpi_tls_profiles::EchFacadeError> {
    let request =
        ripdpi_tls_profiles::EchLookupRequest::new(inner_name, ripdpi_tls_profiles::EchLookupTransport::EncryptedDns);
    match ripdpi_tls_profiles::resolve_outbound_ech(
        &request,
        ripdpi_tls_profiles::EchPolicy::default(),
        &EncryptedDnsEchResolver::adguard(connect_hooks),
    )? {
        ripdpi_tls_profiles::EchSetup::Real(config) => Ok(Some(config)),
        ripdpi_tls_profiles::EchSetup::Grease | ripdpi_tls_profiles::EchSetup::OptedOut => Ok(None),
    }
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
    match exchange_encrypted_dns_query_with_hooks(domain, DNS_RECORD_TYPE_HTTPS, endpoint, transport, connect_hooks) {
        Err(err) => EchResolutionOutcome::ResolutionFailed(err),
        Ok(response) => match extract_ech_config_list_from_https_response(&response) {
            Err(err) => EchResolutionOutcome::ResolutionFailed(err),
            Ok(None) => EchResolutionOutcome::NotPublished,
            Ok(Some(bytes)) => EchResolutionOutcome::Available(bytes),
        },
    }
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
    resolver.exchange_blocking(&packet).map_err(|err| err.to_string())
}

/// Add the DoT TLS profile without replacing caller-owned socket hooks.
fn encrypted_dns_connect_hooks(hooks: EncryptedDnsConnectHooks) -> EncryptedDnsConnectHooks {
    hooks.with_dot_tls_connector_builder(|| {
        ripdpi_tls_profiles::configure_builder("chrome_stable").map_err(|error| error.to_string())
    })
}

pub fn extract_ech_config_list_from_https_response(packet: &[u8]) -> Result<Option<Vec<u8>>, String> {
    Ok(parse_https_service_bindings(packet)
        .map_err(|error| error.to_string())?
        .into_iter()
        .find_map(|record| record.ech_config.map(|config| config.raw_list_bytes)))
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
