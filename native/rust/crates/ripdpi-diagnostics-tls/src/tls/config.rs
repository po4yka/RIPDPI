use std::sync::Arc;

use ripdpi_tls_profiles::{ProfileMetadata, selected_profile_config, selected_profile_metadata};
use rustls::client::danger::ServerCertVerifier;
use rustls::client::{EchConfig, EchMode};
use rustls::pki_types::{EchConfigListBytes, ServerName};
use rustls::{ClientConfig, RootCertStore};

use super::key_log::TlsKeyLogCallback;
use super::types::{ApplicationProtocolPolicy, TlsClientProfile};
use crate::transport::{TargetAddress, TransportConfig};
use ripdpi_diagnostics_dns::cdn_ech::{CdnEchConfig, opportunistic_ech_config_for_ip};
use ripdpi_diagnostics_dns::dns::{
    EchResolutionOutcome, encrypted_dns_endpoint_for_resolver_id,
    resolve_https_ech_configs_via_encrypted_dns_with_endpoint,
};

pub(super) const ECH_CONFIG_UNAVAILABLE_ERROR: &str = "ech_config_unavailable";

pub(super) fn build_standard_client_config_with_key_log(
    profile: TlsClientProfile,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    key_log: Option<&TlsKeyLogCallback>,
    application_protocol: ApplicationProtocolPolicy,
) -> Arc<ClientConfig> {
    let template_profile = planned_tls_template_profile(profile);
    let builder = match profile {
        TlsClientProfile::Auto => ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
            .with_safe_default_protocol_versions()
            .expect("ring provider supports default TLS versions"),
        TlsClientProfile::Tls12Only => {
            ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
                .with_protocol_versions(&[&rustls::version::TLS12])
                .expect("ring provider supports TLS1.2")
        }
        TlsClientProfile::Tls13Only | TlsClientProfile::Tls13WithEch => {
            ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
                .with_protocol_versions(&[&rustls::version::TLS13])
                .expect("ring provider supports TLS1.3")
        }
    };
    let mut config = if let Some(verifier) = tls_verifier {
        builder.dangerous().with_custom_certificate_verifier(verifier.clone()).with_no_client_auth()
    } else {
        builder.with_root_certificates(default_root_store()).with_no_client_auth()
    };
    if let Some(key_log) = key_log {
        config.key_log = key_log.clone();
    }
    apply_application_protocol_policy(&mut config, template_profile, application_protocol);
    Arc::new(config)
}

pub(super) fn build_ech_client_config(
    server_name: &str,
    target: &TargetAddress,
    transport: &TransportConfig,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    key_log: Option<&TlsKeyLogCallback>,
    application_protocol: ApplicationProtocolPolicy,
) -> Result<Arc<ClientConfig>, String> {
    let template_metadata = planned_tls_template_metadata(TlsClientProfile::Tls13WithEch);
    let bootstrap_endpoint = template_metadata
        .template
        .ech_bootstrap_resolver_id
        .map_or_else(|| encrypted_dns_endpoint_for_resolver_id("adguard"), encrypted_dns_endpoint_for_resolver_id);
    let ech_config_list =
        match resolve_https_ech_configs_via_encrypted_dns_with_endpoint(server_name, bootstrap_endpoint, transport) {
            EchResolutionOutcome::Available(bytes) => bytes,
            dns_failure => {
                // Opportunistic fallback: if the target IP belongs to a known CDN
                // that supports ECH, use a hardcoded config instead of giving up.
                if let Some(cdn_config) = resolve_opportunistic_ech(target) {
                    tracing::debug!(
                        server_name,
                        cdn = cdn_config.provider,
                        "ECH DNS resolution unavailable; using opportunistic CDN config"
                    );
                    cdn_config.ech_config_list.to_vec()
                } else {
                    return match dns_failure {
                        EchResolutionOutcome::NotPublished => Err(ECH_CONFIG_UNAVAILABLE_ERROR.to_string()),
                        EchResolutionOutcome::ResolutionFailed(err) => Err(format!("ech_resolution_failed: {err}")),
                        EchResolutionOutcome::Available(_) => unreachable!(),
                    };
                }
            }
        };
    let provider = rustls::crypto::aws_lc_rs::default_provider();
    let ech_config = EchConfig::new(
        EchConfigListBytes::from(ech_config_list),
        rustls::crypto::aws_lc_rs::hpke::ALL_SUPPORTED_SUITES,
    )
    .map_err(|err| err.to_string())?;
    let builder = ClientConfig::builder_with_provider(provider.into())
        .with_ech(EchMode::Enable(ech_config))
        .map_err(|err| err.to_string())?;
    let template_profile = planned_tls_template_profile(TlsClientProfile::Tls13WithEch);
    let mut config = if let Some(verifier) = tls_verifier {
        builder.dangerous().with_custom_certificate_verifier(verifier.clone()).with_no_client_auth()
    } else {
        builder.with_root_certificates(default_root_store()).with_no_client_auth()
    };
    if let Some(key_log) = key_log {
        config.key_log = key_log.clone();
    }
    apply_application_protocol_policy(&mut config, template_profile, application_protocol);
    Ok(Arc::new(config))
}

/// Attempt to find an opportunistic ECH config by checking if the target IP
/// belongs to a known CDN provider.
fn resolve_opportunistic_ech(target: &TargetAddress) -> Option<&'static CdnEchConfig> {
    match target {
        TargetAddress::Ip(ip) => opportunistic_ech_config_for_ip(*ip),
        TargetAddress::Host(_) => None,
    }
}

pub fn make_server_name(name: &str, target: &TargetAddress) -> Result<ServerName<'static>, String> {
    if !name.is_empty()
        && let Ok(server_name) = ServerName::try_from(name.to_string())
    {
        return Ok(server_name);
    }
    match target {
        TargetAddress::Ip(ip) => Ok(ServerName::IpAddress((*ip).into())),
        TargetAddress::Host(host) => ServerName::try_from(host.clone()).map_err(|err| err.to_string()),
    }
}

pub fn default_root_store() -> RootCertStore {
    let mut roots = RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    roots
}
pub fn planned_tls_template_profile(profile: TlsClientProfile) -> &'static str {
    match profile {
        TlsClientProfile::Auto | TlsClientProfile::Tls13Only => "chrome_stable",
        TlsClientProfile::Tls12Only => "chrome_desktop_stable",
        TlsClientProfile::Tls13WithEch => "firefox_ech_stable",
    }
}

pub fn planned_tls_template_metadata(profile: TlsClientProfile) -> ProfileMetadata {
    selected_profile_metadata(planned_tls_template_profile(profile))
}
fn apply_template_alpn(config: &mut ClientConfig, profile_id: &str) {
    config.alpn_protocols = selected_profile_config(profile_id).alpn.iter().map(|protocol| protocol.to_vec()).collect();
}

fn apply_application_protocol_policy(
    config: &mut ClientConfig,
    profile_id: &str,
    application_protocol: ApplicationProtocolPolicy,
) {
    match application_protocol {
        ApplicationProtocolPolicy::TemplateDefault => apply_template_alpn(config, profile_id),
        ApplicationProtocolPolicy::Http11Only => config.alpn_protocols = vec![b"http/1.1".to_vec()],
    }
}
