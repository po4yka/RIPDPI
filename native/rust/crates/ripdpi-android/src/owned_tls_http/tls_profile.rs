use std::io;
use std::time::Duration;

use ripdpi_tls_profiles::{configure_builder, profile_catalog_version, ProfileMetadata, ProfileTemplateMetadata};
use tokio::net::TcpStream;
use tokio::time::timeout;

use crate::owned_tls_http::dto::NativeOwnedTlsHttpResponse;

const HTTP11_ALPN: &[u8] = b"\x08http/1.1";

pub(crate) async fn connect_tls(
    host: &str,
    tcp: TcpStream,
    connect_timeout_ms: u64,
    tls_profile_id: &str,
) -> io::Result<tokio_boring::SslStream<TcpStream>> {
    let mut connector_builder =
        configure_builder(tls_profile_id).map_err(|error| io::Error::other(format!("TLS profile: {error}")))?;
    connector_builder.set_alpn_protos(HTTP11_ALPN).map_err(|error| io::Error::other(format!("TLS ALPN: {error}")))?;
    let ssl =
        connector_builder.build().configure().map_err(|error| io::Error::other(format!("TLS configure: {error}")))?;
    timeout(Duration::from_millis(connect_timeout_ms), tokio_boring::connect(ssl, host, tcp))
        .await
        .map_err(|_| io::Error::new(io::ErrorKind::TimedOut, format!("TLS handshake to {host} timed out")))?
        .map_err(|error| io::Error::new(io::ErrorKind::ConnectionRefused, format!("TLS handshake failed: {error}")))
}

pub(crate) fn profile_catalog_version_string() -> String {
    profile_catalog_version().to_string()
}

pub(crate) fn apply_profile_metadata(response: &mut NativeOwnedTlsHttpResponse, profile_metadata: ProfileMetadata) {
    let template = profile_metadata.template;
    response.tls_profile_id = Some(profile_metadata.profile_name.to_string());
    response.tls_ja3_parity_target = Some(profile_metadata.parity_targets.ja3.to_string());
    response.tls_ja4_parity_target = Some(profile_metadata.parity_targets.ja4.to_string());
    response.tls_browser_family = Some(profile_metadata.parity_targets.browser_family.to_string());
    response.tls_browser_track = Some(profile_metadata.parity_targets.browser_track.to_string());
    apply_template_metadata(response, template);
    response.client_hello_size_hint = Some(profile_metadata.client_hello_size_hint);
    response.client_hello_invariant_status = Some(profile_metadata.invariant_status.as_str().to_string());
}

fn apply_template_metadata(response: &mut NativeOwnedTlsHttpResponse, template: ProfileTemplateMetadata) {
    response.tls_template_alpn = Some(template.alpn_template.to_string());
    response.tls_template_extension_order_family = Some(template.extension_order_family.to_string());
    response.tls_template_grease_style = Some(template.grease_style.to_string());
    response.tls_template_supported_groups_profile = Some(template.supported_groups_profile.to_string());
    response.tls_template_key_share_profile = Some(template.key_share_profile.to_string());
    response.tls_template_record_choreography = Some(template.record_choreography.to_string());
    response.tls_template_ech_capable = Some(template.ech_capable);
    response.tls_template_ech_bootstrap_policy = Some(template.ech_bootstrap_policy.to_string());
    response.tls_template_ech_bootstrap_resolver_id = template.ech_bootstrap_resolver_id.map(ToString::to_string);
    response.tls_template_ech_outer_extension_policy = Some(template.ech_outer_extension_policy.to_string());
}
