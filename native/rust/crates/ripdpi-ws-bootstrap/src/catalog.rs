use ripdpi_proxy_config::ProxyEncryptedDnsContext;

pub(crate) const WS_TUNNEL_PORT: u16 = 443;

const DEFAULT_DOH_URL: &str = "https://cloudflare-dns.com/dns-query";
const DEFAULT_DOH_HOST: &str = "cloudflare-dns.com";
const DEFAULT_DOH_BOOTSTRAP_IPS: &[&str] = &["1.1.1.1", "1.0.0.1"];
pub(crate) const PRIMARY_DOH_RESOLVER_ID: &str = "adguard";
const PRIMARY_DOH_HOST: &str = "dns.adguard-dns.com";
const PRIMARY_DOH_URL: &str = "https://dns.adguard-dns.com/dns-query";
const PRIMARY_DOH_BOOTSTRAP_IPS: &[&str] = &["94.140.14.14", "94.140.15.15"];
pub(crate) const SECONDARY_DOH_RESOLVER_ID: &str = "dnssb";
const SECONDARY_DOH_HOST: &str = "dns.sb";
const SECONDARY_DOH_URL: &str = "https://doh.dns.sb/dns-query";
const SECONDARY_DOH_BOOTSTRAP_IPS: &[&str] = &["185.222.222.222", "45.11.45.11"];

pub(crate) fn default_encrypted_dns_context() -> ProxyEncryptedDnsContext {
    ProxyEncryptedDnsContext {
        resolver_id: Some("cloudflare".to_string()),
        protocol: "doh".to_string(),
        host: DEFAULT_DOH_HOST.to_string(),
        port: WS_TUNNEL_PORT,
        tls_server_name: Some(DEFAULT_DOH_HOST.to_string()),
        bootstrap_ips: DEFAULT_DOH_BOOTSTRAP_IPS.iter().map(ToString::to_string).collect(),
        doh_url: Some(DEFAULT_DOH_URL.to_string()),
        dnscrypt_provider_name: None,
        dnscrypt_public_key: None,
    }
}

pub(crate) fn primary_encrypted_dns_context() -> ProxyEncryptedDnsContext {
    ProxyEncryptedDnsContext {
        resolver_id: Some(PRIMARY_DOH_RESOLVER_ID.to_string()),
        protocol: "doh".to_string(),
        host: PRIMARY_DOH_HOST.to_string(),
        port: WS_TUNNEL_PORT,
        tls_server_name: Some(PRIMARY_DOH_HOST.to_string()),
        bootstrap_ips: PRIMARY_DOH_BOOTSTRAP_IPS.iter().map(ToString::to_string).collect(),
        doh_url: Some(PRIMARY_DOH_URL.to_string()),
        dnscrypt_provider_name: None,
        dnscrypt_public_key: None,
    }
}

pub(crate) fn secondary_encrypted_dns_context() -> ProxyEncryptedDnsContext {
    ProxyEncryptedDnsContext {
        resolver_id: Some(SECONDARY_DOH_RESOLVER_ID.to_string()),
        protocol: "doh".to_string(),
        host: SECONDARY_DOH_HOST.to_string(),
        port: WS_TUNNEL_PORT,
        tls_server_name: Some(SECONDARY_DOH_HOST.to_string()),
        bootstrap_ips: SECONDARY_DOH_BOOTSTRAP_IPS.iter().map(ToString::to_string).collect(),
        doh_url: Some(SECONDARY_DOH_URL.to_string()),
        dnscrypt_provider_name: None,
        dnscrypt_public_key: None,
    }
}
