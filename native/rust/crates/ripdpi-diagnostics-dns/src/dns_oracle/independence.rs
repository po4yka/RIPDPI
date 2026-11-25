use std::net::IpAddr;

use ripdpi_dns_resolver::EncryptedDnsEndpoint;
use ripdpi_ech_dns::{bootstrap_ips_for_resolver, parse_url_host};

pub(super) fn independent_endpoints(
    primary: EncryptedDnsEndpoint,
    fallbacks: &[EncryptedDnsEndpoint],
    max_fallbacks: usize,
) -> Vec<EncryptedDnsEndpoint> {
    let endpoints = std::iter::once(primary).chain(fallbacks.iter().cloned()).collect::<Vec<_>>();
    let mut grouped = vec![false; endpoints.len()];
    let mut independent = Vec::new();
    for index in 0..endpoints.len() {
        if grouped[index] {
            continue;
        }
        if independent.len() > max_fallbacks {
            break;
        }
        independent.push(endpoints[index].clone());
        grouped[index] = true;
        let mut pending = vec![index];
        while let Some(member) = pending.pop() {
            for (other, endpoint) in endpoints.iter().enumerate() {
                if !grouped[other] && same_service(&endpoints[member], endpoint) {
                    grouped[other] = true;
                    pending.push(other);
                }
            }
        }
    }
    independent
}

fn same_service(left: &EncryptedDnsEndpoint, right: &EncryptedDnsEndpoint) -> bool {
    let left_names = service_names(left);
    let right_names = service_names(right);
    left_names.iter().any(|name| right_names.contains(name))
        || provider_id(left).is_some_and(|provider| provider_id(right) == Some(provider))
}

fn service_names(endpoint: &EncryptedDnsEndpoint) -> Vec<String> {
    if let Some(odoh) = &endpoint.odoh {
        return vec![normalized_name(&odoh.target_host)];
    }
    std::iter::once(Some(endpoint.host.as_str()))
        .chain([endpoint.tls_server_name.as_deref(), endpoint.dnscrypt_provider_name.as_deref()])
        .flatten()
        .map(normalized_name)
        .chain(endpoint.doh_url.as_deref().and_then(parse_url_host).map(|name| normalized_name(&name)))
        .filter(|name| !name.is_empty())
        .collect()
}

fn provider_id(endpoint: &EncryptedDnsEndpoint) -> Option<String> {
    if let Some(odoh) = &endpoint.odoh {
        let operator = odoh.target_operator_id.trim().to_ascii_lowercase();
        return (!operator.is_empty()).then_some(operator);
    }
    let id = endpoint.resolver_id.as_deref()?.trim().to_ascii_lowercase();
    if bootstrap_ips_for_resolver(&id).is_empty() {
        return None;
    }
    Some(if id == "google_ip" { "google".to_string() } else { id })
}

fn normalized_name(name: &str) -> String {
    let name = name.trim().trim_end_matches('.').trim_matches(['[', ']']);
    name.parse::<IpAddr>().map_or_else(|_| name.to_ascii_lowercase(), |ip| ip.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::dns_oracle::{DnsOracleConfig, DnsOracleTrust, evaluate_dns_oracles};
    use ripdpi_dns_resolver::EncryptedDnsProtocol;
    use ripdpi_ech_dns::encrypted_dns_endpoint_for_resolver_id;

    fn assessment(
        primary: EncryptedDnsEndpoint,
        fallbacks: &[EncryptedDnsEndpoint],
        max: usize,
    ) -> crate::dns_oracle::DnsOracleAssessment<Vec<String>> {
        evaluate_dns_oracles(
            primary,
            fallbacks,
            max,
            DnsOracleConfig::default(),
            || false,
            |_, _| Ok(vec!["1.1.1.1".to_string()]),
            Clone::clone,
        )
    }

    #[test]
    fn service_aliases_do_not_gain_quorum_or_consume_the_fallback_limit() {
        let primary = encrypted_dns_endpoint_for_resolver_id("adguard");
        let mut alias = primary.clone();
        alias.resolver_id = Some("custom-alias".to_string());
        alias.protocol = EncryptedDnsProtocol::Dot;
        alias.port = 853;
        alias.host = "DNS.ADGUARD-DNS.COM.".to_string();
        alias.tls_server_name = None;
        alias.doh_url = None;

        let repeated = assessment(primary.clone(), std::slice::from_ref(&alias), 1);
        assert_eq!(repeated.trust, DnsOracleTrust::PrimaryOnly);
        assert_eq!(repeated.confidence_score, 70);

        let independent = encrypted_dns_endpoint_for_resolver_id("dnssb");
        let agreed = assessment(primary, &[alias, independent], 1);
        assert_eq!(agreed.trust, DnsOracleTrust::TrustedAgreement);
        assert_eq!(agreed.agreement_resolver_ids, ["adguard", "dnssb"]);
    }

    #[test]
    fn catalog_provider_aliases_do_not_gain_quorum() {
        let repeated = assessment(
            encrypted_dns_endpoint_for_resolver_id("google"),
            &[encrypted_dns_endpoint_for_resolver_id("google_ip")],
            1,
        );
        assert_eq!(repeated.trust, DnsOracleTrust::PrimaryOnly);
        assert_eq!(repeated.agreement_resolver_ids, ["google"]);
    }

    #[test]
    fn alias_bridges_do_not_gain_quorum_in_either_catalog_order() {
        let mut primary = encrypted_dns_endpoint_for_resolver_id("adguard");
        primary.resolver_id = None;
        primary.host = "a.example".to_string();
        primary.tls_server_name = None;
        primary.doh_url = None;
        let mut bridge = primary.clone();
        bridge.tls_server_name = Some("b.example".to_string());
        let mut alias = primary.clone();
        alias.host = "b.example".to_string();
        for fallbacks in [[bridge.clone(), alias.clone()], [alias, bridge]] {
            let repeated = assessment(primary.clone(), &fallbacks, 1);
            assert_eq!(repeated.trust, DnsOracleTrust::PrimaryOnly);
            assert_eq!(repeated.confidence_score, 70);
            assert_eq!(repeated.attempts.len(), 1);
        }
    }

    #[test]
    fn shared_bootstrap_does_not_merge_different_resolver_services() {
        let primary = encrypted_dns_endpoint_for_resolver_id("adguard");
        let mut independent = encrypted_dns_endpoint_for_resolver_id("dnssb");
        independent.bootstrap_ips = primary.bootstrap_ips.clone();
        let agreed = assessment(primary, &[independent], 1);
        assert_eq!(agreed.trust, DnsOracleTrust::TrustedAgreement);
        assert_eq!(agreed.agreement_resolver_ids, ["adguard", "dnssb"]);
    }
}
