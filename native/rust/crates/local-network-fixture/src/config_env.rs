use crate::types::FixtureConfig;

impl FixtureConfig {
    pub fn from_env() -> Self {
        let mut config = Self::default();
        config.bind_host = env_string("RIPDPI_FIXTURE_BIND_HOST", &config.bind_host);
        config.android_host = env_string("RIPDPI_FIXTURE_ANDROID_HOST", &config.android_host);
        config.tcp_echo_port = env_u16("RIPDPI_FIXTURE_TCP_ECHO_PORT", config.tcp_echo_port);
        config.udp_echo_port = env_u16("RIPDPI_FIXTURE_UDP_ECHO_PORT", config.udp_echo_port);
        config.tls_echo_port = env_u16("RIPDPI_FIXTURE_TLS_ECHO_PORT", config.tls_echo_port);
        config.dns_udp_port = env_u16("RIPDPI_FIXTURE_DNS_UDP_PORT", config.dns_udp_port);
        config.dns_http_port = env_u16("RIPDPI_FIXTURE_DNS_HTTP_PORT", config.dns_http_port);
        config.dns_dot_port = env_u16("RIPDPI_FIXTURE_DNS_DOT_PORT", config.dns_dot_port);
        config.dns_dnscrypt_port = env_u16("RIPDPI_FIXTURE_DNS_DNSCRYPT_PORT", config.dns_dnscrypt_port);
        config.dns_doq_port = env_u16("RIPDPI_FIXTURE_DNS_DOQ_PORT", config.dns_doq_port);
        config.dns_odoh_proxy_port = env_u16("RIPDPI_FIXTURE_DNS_ODOH_PROXY_PORT", config.dns_odoh_proxy_port);
        config.dns_odoh_target_port = env_u16("RIPDPI_FIXTURE_DNS_ODOH_TARGET_PORT", config.dns_odoh_target_port);
        config.socks5_port = env_u16("RIPDPI_FIXTURE_SOCKS5_PORT", config.socks5_port);
        config.control_port = env_u16("RIPDPI_FIXTURE_CONTROL_PORT", config.control_port);
        config.fixture_domain = env_string("RIPDPI_FIXTURE_DOMAIN", &config.fixture_domain);
        config.fixture_ipv4 = env_string("RIPDPI_FIXTURE_IPV4", &config.fixture_ipv4);
        config.dns_answer_ipv4 = env_string("RIPDPI_FIXTURE_DNS_ANSWER_IPV4", &config.dns_answer_ipv4);
        config.dnscrypt_provider_name =
            env_string("RIPDPI_FIXTURE_DNSCRYPT_PROVIDER_NAME", &config.dnscrypt_provider_name);
        config.dnscrypt_public_key = env_string("RIPDPI_FIXTURE_DNSCRYPT_PUBLIC_KEY", &config.dnscrypt_public_key);
        config
    }
}

fn env_string(key: &str, default: &str) -> String {
    std::env::var(key).unwrap_or_else(|_| default.to_string())
}

fn env_u16(key: &str, default: u16) -> u16 {
    std::env::var(key).ok().and_then(|value| value.parse::<u16>().ok()).unwrap_or(default)
}
