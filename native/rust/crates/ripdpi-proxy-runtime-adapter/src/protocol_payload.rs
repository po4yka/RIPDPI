use ripdpi_packets::{tls_fake_profile_bytes, tune_tls_padding_size_into, TlsFakeProfile};

/// Build a TLS ClientHello with the given domain as SNI.
///
/// Uses the Google Chrome fake TLS profile, which the SNI replacement function
/// handles reliably, and patches in the probe domain.
pub fn build_probe_client_hello(domain: &str) -> Vec<u8> {
    let template = tls_fake_profile_bytes(TlsFakeProfile::GoogleChrome);
    let capacity = template.len() + domain.len() + 64;
    let mutation = ripdpi_packets::change_tls_sni_seeded_like_c(template, domain.as_bytes(), capacity, 0);
    let mut output = if mutation.rc == 0 {
        mutation.bytes
    } else {
        // If SNI patching fails, use the template as-is. This still exercises
        // the desync pipeline, just with the template SNI.
        template.to_vec()
    };
    avoid_tls_517_size(&mut output);
    output
}

pub fn payload_has_ech(payload: &[u8]) -> bool {
    ripdpi_packets::tls_marker_info(payload).and_then(|markers| markers.ech_ext_start).is_some()
}

pub fn group_accepts_any_or_non_http_tls(group: &ripdpi_config::DesyncGroup) -> bool {
    group.matches.any_protocol
        || group.matches.proto == 0
        || (group.matches.proto & (ripdpi_packets::IS_HTTP | ripdpi_packets::IS_HTTPS)) == 0
}

fn avoid_tls_517_size(output: &mut Vec<u8>) {
    if output.len() == 517 && ripdpi_packets::is_tls_client_hello(output) {
        let _ = tune_tls_padding_size_into(output, 518);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn build_probe_client_hello_produces_valid_tls_record() {
        let hello = build_probe_client_hello("youtube.com");
        assert!(ripdpi_packets::is_tls_client_hello(&hello), "probe payload must be a TLS ClientHello");
    }

    #[test]
    fn build_probe_client_hello_embeds_domain_as_sni() {
        let hello = build_probe_client_hello("discord.com");
        let sni = ripdpi_packets::parse_tls(&hello);
        assert!(sni.is_some(), "SNI should be extractable after patching");
        assert_eq!(sni.unwrap().len(), "discord.com".len(), "SNI length must match domain");
    }

    #[test]
    fn avoid_tls_517_size_retunes_padding_when_needed() {
        let mut hello = tls_fake_profile_bytes(TlsFakeProfile::GoogleChrome).to_vec();
        tune_tls_padding_size_into(&mut hello, 517);
        assert_eq!(hello.len(), 517);

        avoid_tls_517_size(&mut hello);

        assert_ne!(hello.len(), 517);
        assert!(ripdpi_packets::is_tls_client_hello(&hello));
    }
}
