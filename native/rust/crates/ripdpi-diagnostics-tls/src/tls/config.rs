use std::net::SocketAddr;
use std::sync::Arc;

use ripdpi_tls_profiles::ech::{EchSetup, configure_rustls_ech};
use ripdpi_tls_profiles::{ProfileMetadata, selected_profile_config, selected_profile_metadata};
use rustls::client::danger::ServerCertVerifier;
use rustls::client::{EchConfig, EchMode};
use rustls::pki_types::{EchConfigListBytes, ServerName};
use rustls::{ClientConfig, ConfigBuilder, RootCertStore, WantsVerifier};

use super::key_log::TlsKeyLogCallback;
use super::types::{ApplicationProtocolPolicy, EchConfigSource, TlsClientProfile};
use crate::transport::{TargetAddress, TransportConfig};
use ripdpi_diagnostics_dns::cdn_ech::{opportunistic_ech_config_for_ip, production_updater};
use ripdpi_diagnostics_dns::dns::{
    EchResolutionOutcome, encrypted_dns_endpoint_for_resolver_id,
    resolve_https_ech_configs_via_encrypted_dns_with_endpoint_and_abort,
};

#[derive(Clone, Debug, Eq, PartialEq)]
pub(super) enum EchLookupFailure {
    NotPublished,
    ResolutionFailed(String),
    InvalidConfig(String),
}

impl std::fmt::Display for EchLookupFailure {
    fn fmt(&self, fmt: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::NotPublished => fmt.write_str("ech_config_unavailable"),
            Self::ResolutionFailed(error) => write!(fmt, "ech_resolution_failed: {error}"),
            Self::InvalidConfig(error) => write!(fmt, "ech_config_invalid: {error}"),
        }
    }
}

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
        TlsClientProfile::Tls13Only
        | TlsClientProfile::Tls13WithEch
        | TlsClientProfile::ComparisonTls13
        | TlsClientProfile::ComparisonEchGrease
        | TlsClientProfile::ComparisonEch => {
            ClientConfig::builder_with_provider(rustls::crypto::ring::default_provider().into())
                .with_protocol_versions(&[&rustls::version::TLS13])
                .expect("ring provider supports TLS1.3")
        }
    };
    let mut config = if let Some(verifier) = tls_verifier {
        builder.dangerous().with_custom_certificate_verifier(verifier.clone()).with_no_client_auth()
    } else {
        ripdpi_tls_profiles::cert_blocklist::with_blocklisted_roots(builder, default_root_store())
            .expect("bundled webpki roots are non-empty")
            .with_no_client_auth()
    };
    if let Some(key_log) = key_log {
        config.key_log = key_log.clone();
    }
    apply_application_protocol_policy(&mut config, template_profile, application_protocol);
    Arc::new(config)
}

pub(super) fn build_ech_comparison_control_config(
    profile: TlsClientProfile,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    key_log: Option<&TlsKeyLogCallback>,
    application_protocol: ApplicationProtocolPolicy,
    #[cfg(test)] fixture_ech_roots: Option<&RootCertStore>,
) -> Result<Arc<ClientConfig>, String> {
    let builder = ClientConfig::builder_with_provider(rustls::crypto::aws_lc_rs::default_provider().into());
    let builder = if matches!(profile, TlsClientProfile::ComparisonEchGrease) {
        configure_rustls_ech(builder, &EchSetup::Grease).map_err(|error| error.to_string())?
    } else {
        builder.with_protocol_versions(&[&rustls::version::TLS13]).map_err(|error| error.to_string())?
    };
    finish_aws_lc_client_config(
        builder,
        profile,
        tls_verifier,
        key_log,
        application_protocol,
        #[cfg(test)]
        fixture_ech_roots,
    )
}

pub(super) fn resolve_ech_config(
    server_name: &str,
    port: Option<u16>,
    connected_addr: Option<SocketAddr>,
    transport: &TransportConfig,
    should_abort: &dyn Fn() -> Option<&'static str>,
    #[cfg(test)] fixture_ech_lookup: Option<&EchResolutionOutcome>,
) -> Result<(Vec<u8>, EchConfigSource), EchLookupFailure> {
    let template_metadata = planned_tls_template_metadata(TlsClientProfile::Tls13WithEch);
    let resolver_id = template_metadata.template.ech_bootstrap_resolver_id.unwrap_or("adguard");
    let bootstrap_endpoint = encrypted_dns_endpoint_for_resolver_id(resolver_id);
    let lookup = || {
        resolve_https_ech_configs_via_encrypted_dns_with_endpoint_and_abort(
            server_name,
            port,
            bootstrap_endpoint,
            transport,
            should_abort,
        )
    };
    #[cfg(not(test))]
    let lookup = lookup();
    #[cfg(test)]
    let lookup = fixture_ech_lookup.cloned().unwrap_or_else(lookup);
    select_ech_config(lookup, connected_addr)
}

fn select_ech_config(
    lookup: EchResolutionOutcome,
    connected_addr: Option<SocketAddr>,
) -> Result<(Vec<u8>, EchConfigSource), EchLookupFailure> {
    let failure = match lookup {
        EchResolutionOutcome::Available(bytes) => return Ok((bytes, EchConfigSource::DnsHttps)),
        EchResolutionOutcome::NotPublished => EchLookupFailure::NotPublished,
        EchResolutionOutcome::ResolutionFailed(error) => EchLookupFailure::ResolutionFailed(error),
        EchResolutionOutcome::InvalidConfig(error) => EchLookupFailure::InvalidConfig(error),
    };
    let config = connected_addr.and_then(|peer| opportunistic_ech_config_for_ip(peer.ip())).ok_or(failure)?;
    let bytes = production_updater().cached_config().unwrap_or_else(|| config.ech_config_list.to_vec());
    Ok((bytes, EchConfigSource::CdnFallback(config.provider)))
}

pub(super) fn build_ech_client_config_from_list(
    ech_config_list: Vec<u8>,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    key_log: Option<&TlsKeyLogCallback>,
    application_protocol: ApplicationProtocolPolicy,
    #[cfg(test)] fixture_ech_roots: Option<&RootCertStore>,
) -> Result<Arc<ClientConfig>, String> {
    let provider = rustls::crypto::aws_lc_rs::default_provider();
    let ech_config = EchConfig::new(
        EchConfigListBytes::from(ech_config_list),
        rustls::crypto::aws_lc_rs::hpke::ALL_SUPPORTED_SUITES,
    )
    .map_err(|err| err.to_string())?;
    let builder = ClientConfig::builder_with_provider(provider.into())
        .with_ech(EchMode::Enable(ech_config))
        .map_err(|err| err.to_string())?;
    finish_aws_lc_client_config(
        builder,
        TlsClientProfile::Tls13WithEch,
        tls_verifier,
        key_log,
        application_protocol,
        #[cfg(test)]
        fixture_ech_roots,
    )
}

fn finish_aws_lc_client_config(
    builder: ConfigBuilder<ClientConfig, WantsVerifier>,
    profile: TlsClientProfile,
    tls_verifier: Option<&Arc<dyn ServerCertVerifier>>,
    key_log: Option<&TlsKeyLogCallback>,
    application_protocol: ApplicationProtocolPolicy,
    #[cfg(test)] fixture_ech_roots: Option<&RootCertStore>,
) -> Result<Arc<ClientConfig>, String> {
    let mut config = if let Some(verifier) = tls_verifier {
        builder.dangerous().with_custom_certificate_verifier(verifier.clone()).with_no_client_auth()
    } else {
        let roots = default_root_store();
        #[cfg(test)]
        let roots = fixture_ech_roots.cloned().unwrap_or(roots);
        ripdpi_tls_profiles::cert_blocklist::with_blocklisted_roots(builder, roots)
            .map_err(|err| err.to_string())?
            .with_no_client_auth()
    };
    if let Some(key_log) = key_log {
        config.key_log = key_log.clone();
    }
    apply_application_protocol_policy(&mut config, planned_tls_template_profile(profile), application_protocol);
    Ok(Arc::new(config))
}

pub fn make_server_name(name: &str, target: &TargetAddress) -> Result<ServerName<'static>, String> {
    let name = if name.is_empty() {
        match target {
            TargetAddress::Ip(ip) => return Ok(ServerName::IpAddress((*ip).into())),
            TargetAddress::Host(host) => host,
        }
    } else {
        name
    };
    let name = ripdpi_tls_profiles::normalize_server_name(name).map_err(|error| error.to_string())?;
    ServerName::try_from(name).map_err(|error| error.to_string())
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
        TlsClientProfile::Tls13WithEch
        | TlsClientProfile::ComparisonTls13
        | TlsClientProfile::ComparisonEchGrease
        | TlsClientProfile::ComparisonEch => "firefox_ech_stable",
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn comparison_controls_emit_client_hellos_without_and_with_the_ech_extension() {
        for (profile, expected_ech) in
            [(TlsClientProfile::ComparisonTls13, false), (TlsClientProfile::ComparisonEchGrease, true)]
        {
            let config =
                build_ech_comparison_control_config(profile, None, None, ApplicationProtocolPolicy::Http11Only, None)
                    .unwrap();
            let name = ServerName::try_from("inner.example").unwrap();
            let mut client = rustls::ClientConnection::new(config, name).unwrap();
            let mut hello = Vec::new();
            client.write_tls(&mut hello).unwrap();

            let plan = ripdpi_tls_profiles::plan_first_flight(planned_tls_template_profile(profile), &hello).unwrap();
            assert_eq!(plan.ech_present_in_input, expected_ech, "{profile:?}");
        }
    }

    #[test]
    fn ech_fallback_requires_the_connected_cdn_peer() {
        let cloudflare = Some("104.16.0.1:443".parse().unwrap());
        let other = Some("192.0.2.1:443".parse().unwrap());
        assert!(matches!(
            select_ech_config(EchResolutionOutcome::NotPublished, cloudflare),
            Ok((bytes, EchConfigSource::CdnFallback("Cloudflare"))) if !bytes.is_empty()
        ));
        assert_eq!(select_ech_config(EchResolutionOutcome::NotPublished, other), Err(EchLookupFailure::NotPublished));
        assert_eq!(select_ech_config(EchResolutionOutcome::NotPublished, None), Err(EchLookupFailure::NotPublished));
        assert_eq!(
            select_ech_config(EchResolutionOutcome::Available(vec![1]), cloudflare),
            Ok((vec![1], EchConfigSource::DnsHttps))
        );
    }

    #[test]
    fn opportunistic_fallback_uses_the_restored_cdn_configuration() {
        use std::time::{SystemTime, UNIX_EPOCH};

        let peer = "104.16.1.1:443".parse::<SocketAddr>().unwrap();
        let bundled = opportunistic_ech_config_for_ip(peer.ip()).unwrap().ech_config_list.to_vec();
        let mut restored = bundled.clone();
        restored[6] ^= 1;
        let fetched_at = u64::try_from(SystemTime::now().duration_since(UNIX_EPOCH).unwrap().as_millis()).unwrap();
        let updater = production_updater();
        let previous = updater.snapshot_for_persistence();
        updater.seed_from_persisted(restored.clone(), fetched_at).unwrap();

        let selected = select_ech_config(EchResolutionOutcome::NotPublished, Some(peer)).map(|(bytes, _)| bytes);
        if let Some(previous) = previous {
            updater.seed_from_persisted(previous.config, previous.fetched_at_unix_ms).unwrap();
        } else {
            updater.seed_from_persisted(bundled, fetched_at).unwrap();
        }

        let selected = selected.expect("the known connected CDN peer has a fallback");
        assert_eq!(selected, restored, "the consumer must use the restored bytes");
        build_ech_client_config_from_list(selected, None, None, ApplicationProtocolPolicy::TemplateDefault, None)
            .expect("the restored config must reach the real TLS config builder");
    }
}
