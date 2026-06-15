use std::net::SocketAddr;
use std::sync::Arc;
use std::time::Duration;

use reqwest::{Client, Proxy};
use ripdpi_tls_profiles::EchSetup;
use rustls::client::danger::ServerCertVerifier;
use rustls::pki_types::CertificateDer;
use rustls::{ClientConfig, RootCertStore};
use url::Url;

use crate::health::HealthRegistry;
use crate::types::{EncryptedDnsEndpoint, EncryptedDnsError, EncryptedDnsTransport, ResolverNetworkScope};

pub(crate) fn build_client_config(
    verifier: Option<&Arc<dyn ServerCertVerifier>>,
    extra_roots: &[CertificateDer<'static>],
) -> Result<Arc<ClientConfig>, EncryptedDnsError> {
    let builder = ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
        .with_safe_default_protocol_versions()
        .expect("ring provider supports default TLS versions");
    if let Some(verifier) = verifier {
        Ok(Arc::new(builder.dangerous().with_custom_certificate_verifier(verifier.clone()).with_no_client_auth()))
    } else {
        Ok(Arc::new(builder.with_root_certificates(default_root_store(extra_roots)?).with_no_client_auth()))
    }
}

pub(crate) fn build_doh_client(
    endpoint: &EncryptedDnsEndpoint,
    transport: &EncryptedDnsTransport,
    timeout: Duration,
    tls_roots: &[CertificateDer<'static>],
    health: Option<&HealthRegistry>,
    network_scope: &ResolverNetworkScope,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> Result<Client, EncryptedDnsError> {
    let mut builder = Client::builder().timeout(timeout).connect_timeout(timeout);

    if doh_uses_tls(endpoint) {
        builder = builder.use_preconfigured_tls(doh_tls_config(tls_roots, tls_verifier)?);
    } else if tls_verifier.is_some() {
        // Plain HTTP DoH is used only in local tests; custom TLS config is irrelevant there.
    } else {
        builder = add_reqwest_roots(builder, tls_roots)?;
    }

    builder = configure_transport(builder, endpoint, transport, health, network_scope)?;
    builder.build().map_err(|err| EncryptedDnsError::ClientBuild(err.to_string()))
}

fn default_root_store(extra_roots: &[CertificateDer<'static>]) -> Result<RootCertStore, EncryptedDnsError> {
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    for certificate in extra_roots {
        // Propagate rather than silently drop: a malformed caller-supplied extra
        // trust anchor must surface as a config error, not be quietly skipped
        // (which would leave the resolver trusting fewer roots than configured).
        roots.add(certificate.clone()).map_err(|err| EncryptedDnsError::ClientBuild(format!("add TLS root: {err}")))?;
    }
    Ok(roots)
}

fn doh_uses_tls(endpoint: &EncryptedDnsEndpoint) -> bool {
    endpoint_doh_url(endpoint)
        .and_then(|value| Url::parse(value).ok())
        .is_none_or(|url| url.scheme().eq_ignore_ascii_case("https"))
}

fn endpoint_doh_url(endpoint: &EncryptedDnsEndpoint) -> Option<&str> {
    endpoint.odoh.as_ref().map(|config| config.proxy_url.as_str()).or(endpoint.doh_url.as_deref())
}

fn doh_tls_config(
    tls_roots: &[CertificateDer<'static>],
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
) -> Result<ClientConfig, EncryptedDnsError> {
    doh_tls_config_with_ech_setup(tls_roots, tls_verifier, &EchSetup::Grease)
}

fn doh_tls_config_with_ech_setup(
    tls_roots: &[CertificateDer<'static>],
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    setup: &EchSetup,
) -> Result<ClientConfig, EncryptedDnsError> {
    let builder = ripdpi_tls_profiles::configure_rustls_ech(
        ClientConfig::builder_with_provider(rustls::crypto::aws_lc_rs::default_provider().into()),
        setup,
    )
    .map_err(|err| EncryptedDnsError::ClientBuild(format!("DoH ECH setup: {err}")))?;
    let mut config = if let Some(verifier) = tls_verifier {
        builder.dangerous().with_custom_certificate_verifier(verifier.clone()).with_no_client_auth()
    } else {
        builder.with_root_certificates(default_root_store(tls_roots)?).with_no_client_auth()
    };
    config.alpn_protocols = vec![b"h2".to_vec(), b"http/1.1".to_vec()];
    Ok(config)
}

fn add_reqwest_roots(
    mut builder: reqwest::ClientBuilder,
    tls_roots: &[CertificateDer<'static>],
) -> Result<reqwest::ClientBuilder, EncryptedDnsError> {
    for certificate in tls_roots {
        let reqwest_certificate = reqwest::Certificate::from_der(certificate.as_ref())
            .map_err(|err| EncryptedDnsError::ClientBuild(err.to_string()))?;
        builder = builder.add_root_certificate(reqwest_certificate);
    }
    Ok(builder)
}

fn configure_transport(
    builder: reqwest::ClientBuilder,
    endpoint: &EncryptedDnsEndpoint,
    transport: &EncryptedDnsTransport,
    health: Option<&HealthRegistry>,
    network_scope: &ResolverNetworkScope,
) -> Result<reqwest::ClientBuilder, EncryptedDnsError> {
    match transport {
        EncryptedDnsTransport::Direct => Ok(configure_direct_transport(builder, endpoint, health, network_scope)),
        EncryptedDnsTransport::Socks5 { host, port } => configure_socks5_transport(builder, host, *port),
    }
}

fn configure_direct_transport(
    builder: reqwest::ClientBuilder,
    endpoint: &EncryptedDnsEndpoint,
    health: Option<&HealthRegistry>,
    network_scope: &ResolverNetworkScope,
) -> reqwest::ClientBuilder {
    let ips = if let Some(h) = health {
        h.rank_bootstrap_ips_in_scope(network_scope, &endpoint.bootstrap_ips)
    } else {
        endpoint.bootstrap_ips.clone()
    };
    let addresses = ips.iter().copied().map(|ip| SocketAddr::new(ip, endpoint.port)).collect::<Vec<_>>();
    builder.resolve_to_addrs(endpoint.host.as_str(), &addresses)
}

fn configure_socks5_transport(
    builder: reqwest::ClientBuilder,
    host: &str,
    port: u16,
) -> Result<reqwest::ClientBuilder, EncryptedDnsError> {
    let proxy = Proxy::all(format!("socks5h://{host}:{port}"))
        .map_err(|err| EncryptedDnsError::ClientBuild(err.to_string()))?;
    Ok(builder.proxy(proxy))
}

#[cfg(test)]
mod tests {
    use super::*;
    use ripdpi_tls_profiles::EchSetup;

    #[test]
    fn doh_tls_config_uses_ech_facade_grease_for_https_resolver() {
        let config = doh_tls_config_with_ech_setup(&[], None, &EchSetup::Grease).expect("ECH DoH TLS config");

        assert_eq!(config.alpn_protocols, vec![b"h2".to_vec(), b"http/1.1".to_vec()]);
        assert!(rustls_client_hello_has_ech_extension(config), "DoH outbound TLS must send ECH or GREASE");
    }

    #[test]
    fn build_client_config_surfaces_malformed_extra_root() {
        // A malformed extra trust anchor must now surface as a ClientBuild error
        // rather than being silently skipped (the pre-fix `let _ = roots.add(..)`).
        let bogus = CertificateDer::from(vec![0x00, 0x01, 0x02, 0x03]);
        let result = build_client_config(None, std::slice::from_ref(&bogus));
        assert!(
            matches!(result, Err(EncryptedDnsError::ClientBuild(_))),
            "malformed extra TLS root must surface as ClientBuild, not be silently dropped"
        );
    }

    #[test]
    fn build_client_config_accepts_no_extra_roots() {
        assert!(build_client_config(None, &[]).is_ok(), "empty extra roots must build");
    }

    fn rustls_client_hello_has_ech_extension(config: ClientConfig) -> bool {
        let server_name = rustls::pki_types::ServerName::try_from("resolver.example").expect("server name");
        let mut conn = rustls::ClientConnection::new(Arc::new(config), server_name).expect("client conn");
        let mut bytes = Vec::new();
        conn.write_tls(&mut bytes).expect("write ClientHello");
        let layout = ripdpi_packets::parse_tls_client_hello_layout(&bytes).expect("parse ClientHello");
        layout.extensions.iter().any(|extension| extension.ext_type == 0xfe0d)
    }
}
