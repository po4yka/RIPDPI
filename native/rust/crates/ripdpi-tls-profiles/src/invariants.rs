use crate::{Error, profile::ProfileConfig};

pub(crate) fn validate_profile_config(config: &ProfileConfig) -> Result<(), Error> {
    if config.client_hello_size_hint == 517 {
        return Err(Error::Invariant {
            profile: config.name,
            reason: "517-byte ClientHello is blocked by known middlebox fingerprinting rules",
        });
    }
    if config.client_hello_size_hint < 480 || config.client_hello_size_hint > 540 {
        return Err(Error::Invariant {
            profile: config.name,
            reason: "ClientHello size hint drifted outside the expected mimicry envelope",
        });
    }
    let supports_h2 = config.alpn.iter().any(|value| *value == b"h2");
    let supports_http11 = config.alpn.iter().any(|value| *value == b"http/1.1");
    if !supports_h2 || !supports_http11 {
        return Err(Error::Invariant { profile: config.name, reason: "ALPN list must include both h2 and http/1.1" });
    }
    if config.alpn_template.is_empty()
        || config.extension_order_family.is_empty()
        || config.grease_style.is_empty()
        || config.supported_groups_profile.is_empty()
        || config.key_share_profile.is_empty()
        || config.record_choreography.is_empty()
        || config.ech_bootstrap_policy.is_empty()
        || config.ech_outer_extension_policy.is_empty()
    {
        return Err(Error::Invariant {
            profile: config.name,
            reason: "template metadata must remain explicit for diagnostics and catalog export",
        });
    }
    if config.ja3_parity_target.is_empty() || config.ja4_parity_target.is_empty() {
        return Err(Error::Invariant {
            profile: config.name,
            reason: "JA3/JA4 parity targets must be declared for telemetry and diagnostics",
        });
    }
    Ok(())
}
