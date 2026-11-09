use crate::{Error, profile::ProfileConfig};

pub(crate) fn validate_profile_config(config: &ProfileConfig) -> Result<(), Error> {
    if config.alpn.iter().any(|protocol| protocol.is_empty() || protocol.len() > u8::MAX as usize) {
        return Err(Error::Invariant { profile: config.name, reason: "ALPN identifiers must contain 1..255 bytes" });
    }
    let alpn_len = config.alpn.iter().try_fold(0_usize, |length, protocol| length.checked_add(1 + protocol.len()));
    if alpn_len.is_none_or(|length| length > u16::MAX as usize - 2) {
        return Err(Error::Invariant {
            profile: config.name,
            reason: "ALPN list exceeds the TLS extension data limit",
        });
    }
    if config.client_hello_size_hint == 517 {
        return Err(Error::Invariant {
            profile: config.name,
            reason: "ClientHello reference size hint must not equal 517",
        });
    }
    if config.client_hello_size_hint < 480 || config.client_hello_size_hint > 540 {
        return Err(Error::Invariant {
            profile: config.name,
            reason: "ClientHello size hint drifted outside the expected reference envelope",
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn profile_rejects_invalid_alpn_identifier_lengths() {
        static EMPTY: &[&[u8]] = &[b"h2", b"http/1.1", b""];
        static OVERLONG: &[&[u8]] = &[b"h2", b"http/1.1", &[b'x'; 258]];
        for alpn in [EMPTY, OVERLONG] {
            let mut config = crate::chrome::CHROME_LATEST;
            config.alpn = alpn;
            assert!(validate_profile_config(&config).is_err());
        }
    }

    #[test]
    fn profile_rejects_an_alpn_list_larger_than_extension_data() {
        static PROTOCOL: [u8; 255] = [b'x'; 255];
        static PROTOCOLS: [&[u8]; 258] = {
            let mut protocols: [&[u8]; 258] = [&PROTOCOL; 258];
            protocols[0] = b"h2";
            protocols[1] = b"http/1.1";
            protocols
        };
        let mut config = crate::chrome::CHROME_LATEST;
        config.alpn = &PROTOCOLS;
        assert!(validate_profile_config(&config).is_err());
    }

    #[test]
    fn profile_accepts_a_255_byte_opaque_alpn_identifier() {
        let mut config = crate::chrome::CHROME_LATEST;
        config.alpn = &[b"h2", b"http/1.1", &[0xff; 255]];
        assert!(validate_profile_config(&config).is_ok());
    }
}
