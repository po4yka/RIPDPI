use std::time::Duration;

use serde::{Deserialize, Serialize};

use crate::util;

pub(crate) const IO_POLL_DELAY: Duration = Duration::from_millis(20);
pub(crate) const IO_TIMEOUT: Duration = Duration::from_millis(200);
pub(crate) const SOCKS_IO_TIMEOUT: Duration = Duration::from_secs(3);

pub const DEFAULT_BIND_HOST: &str = "127.0.0.1";
pub const DEFAULT_TCP_ECHO_PORT: u16 = 46001;
pub const DEFAULT_UDP_ECHO_PORT: u16 = 46002;
pub const DEFAULT_TLS_ECHO_PORT: u16 = 46003;
pub const DEFAULT_DNS_UDP_PORT: u16 = 46053;
pub const DEFAULT_DNS_HTTP_PORT: u16 = 46054;
pub const DEFAULT_DNS_DOT_PORT: u16 = 46055;
pub const DEFAULT_DNS_DNSCRYPT_PORT: u16 = 46056;
pub const DEFAULT_DNS_DOQ_PORT: u16 = 46057;
pub const DEFAULT_SOCKS5_PORT: u16 = 46080;
pub const DEFAULT_CONTROL_PORT: u16 = 46090;
pub const DEFAULT_FIXTURE_DOMAIN: &str = "fixture.test";
pub const DEFAULT_FIXTURE_IPV4: &str = "198.18.0.10";
pub const DEFAULT_DNS_ANSWER_IPV4: &str = "198.18.0.10";
pub const DEFAULT_ANDROID_HOST: &str = "10.0.2.2";
pub const DEFAULT_DNSCRYPT_PROVIDER_NAME: &str = "2.dnscrypt-cert.fixture.test";
pub const DEFAULT_DNSCRYPT_PUBLIC_KEY: &str = "ea4a6c63e29c520abef5507b132ec5f9954776aebebe7b92421eea691446d22c";

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct FixtureConfig {
    pub bind_host: String,
    pub android_host: String,
    pub tcp_echo_port: u16,
    pub udp_echo_port: u16,
    pub tls_echo_port: u16,
    pub dns_udp_port: u16,
    pub dns_http_port: u16,
    pub dns_dot_port: u16,
    pub dns_dnscrypt_port: u16,
    pub dns_doq_port: u16,
    pub socks5_port: u16,
    pub control_port: u16,
    pub fixture_domain: String,
    pub fixture_ipv4: String,
    pub dns_answer_ipv4: String,
    pub dnscrypt_provider_name: String,
    pub dnscrypt_public_key: String,
}

impl Default for FixtureConfig {
    fn default() -> Self {
        Self {
            bind_host: DEFAULT_BIND_HOST.to_string(),
            android_host: DEFAULT_ANDROID_HOST.to_string(),
            tcp_echo_port: DEFAULT_TCP_ECHO_PORT,
            udp_echo_port: DEFAULT_UDP_ECHO_PORT,
            tls_echo_port: DEFAULT_TLS_ECHO_PORT,
            dns_udp_port: DEFAULT_DNS_UDP_PORT,
            dns_http_port: DEFAULT_DNS_HTTP_PORT,
            dns_dot_port: DEFAULT_DNS_DOT_PORT,
            dns_dnscrypt_port: DEFAULT_DNS_DNSCRYPT_PORT,
            dns_doq_port: DEFAULT_DNS_DOQ_PORT,
            socks5_port: DEFAULT_SOCKS5_PORT,
            control_port: DEFAULT_CONTROL_PORT,
            fixture_domain: DEFAULT_FIXTURE_DOMAIN.to_string(),
            fixture_ipv4: DEFAULT_FIXTURE_IPV4.to_string(),
            dns_answer_ipv4: DEFAULT_DNS_ANSWER_IPV4.to_string(),
            dnscrypt_provider_name: DEFAULT_DNSCRYPT_PROVIDER_NAME.to_string(),
            dnscrypt_public_key: DEFAULT_DNSCRYPT_PUBLIC_KEY.to_string(),
        }
    }
}

impl FixtureConfig {
    pub fn from_env() -> Self {
        let mut config = Self::default();
        config.bind_host = util::env_string("RIPDPI_FIXTURE_BIND_HOST", &config.bind_host);
        config.android_host = util::env_string("RIPDPI_FIXTURE_ANDROID_HOST", &config.android_host);
        config.tcp_echo_port = util::env_u16("RIPDPI_FIXTURE_TCP_ECHO_PORT", config.tcp_echo_port);
        config.udp_echo_port = util::env_u16("RIPDPI_FIXTURE_UDP_ECHO_PORT", config.udp_echo_port);
        config.tls_echo_port = util::env_u16("RIPDPI_FIXTURE_TLS_ECHO_PORT", config.tls_echo_port);
        config.dns_udp_port = util::env_u16("RIPDPI_FIXTURE_DNS_UDP_PORT", config.dns_udp_port);
        config.dns_http_port = util::env_u16("RIPDPI_FIXTURE_DNS_HTTP_PORT", config.dns_http_port);
        config.dns_dot_port = util::env_u16("RIPDPI_FIXTURE_DNS_DOT_PORT", config.dns_dot_port);
        config.dns_dnscrypt_port = util::env_u16("RIPDPI_FIXTURE_DNS_DNSCRYPT_PORT", config.dns_dnscrypt_port);
        config.dns_doq_port = util::env_u16("RIPDPI_FIXTURE_DNS_DOQ_PORT", config.dns_doq_port);
        config.socks5_port = util::env_u16("RIPDPI_FIXTURE_SOCKS5_PORT", config.socks5_port);
        config.control_port = util::env_u16("RIPDPI_FIXTURE_CONTROL_PORT", config.control_port);
        config.fixture_domain = util::env_string("RIPDPI_FIXTURE_DOMAIN", &config.fixture_domain);
        config.fixture_ipv4 = util::env_string("RIPDPI_FIXTURE_IPV4", &config.fixture_ipv4);
        config.dns_answer_ipv4 = util::env_string("RIPDPI_FIXTURE_DNS_ANSWER_IPV4", &config.dns_answer_ipv4);
        config.dnscrypt_provider_name =
            util::env_string("RIPDPI_FIXTURE_DNSCRYPT_PROVIDER_NAME", &config.dnscrypt_provider_name);
        config.dnscrypt_public_key =
            util::env_string("RIPDPI_FIXTURE_DNSCRYPT_PUBLIC_KEY", &config.dnscrypt_public_key);
        config
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct FixtureManifest {
    pub bind_host: String,
    pub android_host: String,
    pub tcp_echo_port: u16,
    pub udp_echo_port: u16,
    pub tls_echo_port: u16,
    pub dns_udp_port: u16,
    pub dns_http_port: u16,
    pub dns_dot_port: u16,
    pub dns_dnscrypt_port: u16,
    pub dns_doq_port: u16,
    pub socks5_port: u16,
    pub control_port: u16,
    pub fixture_domain: String,
    pub fixture_ipv4: String,
    pub dns_answer_ipv4: String,
    pub tls_certificate_pem: String,
    pub dnscrypt_provider_name: String,
    pub dnscrypt_public_key: String,
}

impl FixtureManifest {
    pub fn control_url_for_host(&self, host: &str) -> String {
        format!("http://{host}:{}", self.control_port)
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct FixtureEvent {
    pub service: String,
    pub protocol: String,
    pub peer: String,
    pub target: String,
    pub detail: String,
    pub bytes: usize,
    pub sni: Option<String>,
    pub created_at: u64,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum FixtureFaultScope {
    OneShot,
    Persistent,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum FixtureFaultTarget {
    TcpEcho,
    UdpEcho,
    TlsEcho,
    DnsUdp,
    DnsHttp,
    DnsDot,
    DnsDnsCrypt,
    DnsDoq,
    Socks5Relay,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum FixtureFaultOutcome {
    TcpReset,
    TcpTruncate,
    UdpDrop,
    UdpDelay,
    TlsAbort,
    DnsNxDomain,
    DnsServFail,
    DnsTimeout,
    SocksRejectConnect,
    TcpThrottle,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct FixtureFaultSpec {
    pub target: FixtureFaultTarget,
    pub outcome: FixtureFaultOutcome,
    pub scope: FixtureFaultScope,
    pub delay_ms: Option<u64>,
}
