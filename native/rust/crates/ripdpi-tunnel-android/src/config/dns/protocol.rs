use ripdpi_tunnel_config::MapDnsConfig;

pub(crate) fn mapdns_resolver_protocol(mapdns: &MapDnsConfig) -> Option<String> {
    mapdns.encrypted_dns_protocol.clone().or_else(|| mapdns.encrypted_dns_doh_url.as_ref().map(|_| "doh".to_string()))
}
