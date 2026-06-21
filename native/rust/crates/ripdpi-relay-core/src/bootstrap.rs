use std::io;

use crate::config::ResolvedRelayRuntimeConfig;

/// Prepare relay endpoint fields before backend construction.
///
/// This layer deliberately does not resolve hostnames. Earlier revisions used
/// `tokio::net::lookup_host` here and logged the relay authority, which selected
/// the device/system DNS path before the relay transport had a protected or
/// owner-selected resolver and leaked the configured endpoint into telemetry.
/// Endpoint names now stay intact; protocol backends own any required
/// connection-time resolution through their protected transport path.
pub(crate) async fn bootstrap_relay_endpoints(
    config: &ResolvedRelayRuntimeConfig,
) -> io::Result<ResolvedRelayRuntimeConfig> {
    Ok(config.clone())
}
