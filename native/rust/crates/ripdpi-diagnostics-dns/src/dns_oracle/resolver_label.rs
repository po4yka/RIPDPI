use ripdpi_dns_resolver::EncryptedDnsEndpoint;

pub(super) fn resolver_label(endpoint: &EncryptedDnsEndpoint) -> String {
    endpoint
        .resolver_id
        .clone()
        .or_else(|| (!endpoint.host.is_empty()).then(|| endpoint.host.clone()))
        .unwrap_or_else(|| endpoint.protocol.as_str().to_string())
}
