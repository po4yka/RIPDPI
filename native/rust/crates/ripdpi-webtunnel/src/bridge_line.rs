use std::collections::BTreeMap;

use serde::Serialize;
use thiserror::Error;
use url::Url;

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
pub struct WebTunnelBridgeConfig {
    pub url: String,
    pub version: Option<String>,
    pub addr: String,
    pub servername: String,
    pub utls: String,
    pub tls_profile: String,
    pub http_host: String,
    pub secret_path: String,
    pub cert: Option<String>,
    pub cert_domain: Option<String>,
    /// Parent's `VpnService.protect` UDS path (`RIPDPI_PROTECT_PATH`), used to
    /// protect the outbound bridge socket so it bypasses the app TUN. `None`
    /// when the env is unset (desktop / proxy-only / VPN down). Not part of the
    /// bridge-line contract, so it is excluded from serialization (goldens,
    /// telemetry) via `#[serde(skip)]`.
    #[serde(skip)]
    pub protect_path: Option<String>,
}

#[derive(Debug, Error, PartialEq, Eq)]
pub enum BridgeLineError {
    #[error("bridge line is empty")]
    Empty,
    #[error("bridge line must declare webtunnel transport")]
    WrongTransport,
    #[error("bridge line is missing endpoint")]
    MissingEndpoint,
    #[error("bridge endpoint must be host:port")]
    InvalidEndpoint,
    #[error("bridge line option is malformed: {0}")]
    MalformedOption(String),
    #[error("bridge line must include url=<https-url>")]
    MissingUrl,
    #[error("url must be a valid absolute URL: {0}")]
    InvalidUrl(String),
    #[error("url scheme must be https")]
    NonHttpsUrl,
    #[error("url must include a host")]
    MissingUrlHost,
    #[error("url must include a non-root secret path")]
    MissingSecretPath,
    #[error("{0} must not be empty")]
    EmptyValue(&'static str),
    #[error("unsupported utls fingerprint: {0}")]
    UnsupportedUtls(String),
}

pub fn parse_bridge_line(raw_bridge_line: &str) -> Result<WebTunnelBridgeConfig, BridgeLineError> {
    let tokens = raw_bridge_line.split_whitespace().filter(|token| !token.is_empty()).collect::<Vec<_>>();
    if tokens.is_empty() {
        return Err(BridgeLineError::Empty);
    }

    let transport_index = usize::from(tokens[0].eq_ignore_ascii_case("Bridge"));
    if tokens.get(transport_index).copied() != Some("webtunnel") {
        return Err(BridgeLineError::WrongTransport);
    }
    let endpoint = tokens.get(transport_index + 1).ok_or(BridgeLineError::MissingEndpoint)?;
    validate_endpoint(endpoint)?;

    let options = parse_options(&tokens[transport_index + 2..])?;
    let raw_url = required_option(&options, "url")?;
    let url = Url::parse(raw_url).map_err(|error| BridgeLineError::InvalidUrl(error.to_string()))?;
    if url.scheme() != "https" {
        return Err(BridgeLineError::NonHttpsUrl);
    }
    let host = url.host_str().ok_or(BridgeLineError::MissingUrlHost)?;
    if url.path() == "/" || url.path().is_empty() {
        return Err(BridgeLineError::MissingSecretPath);
    }

    let version = optional_non_empty(&options, "version")?.map(ToOwned::to_owned);
    let addr = optional_non_empty(&options, "addr")?
        .map_or_else(|| default_addr(host, url.port().unwrap_or(443)), ToOwned::to_owned);
    let servername = optional_non_empty(&options, "servername")?.unwrap_or(host).to_owned();
    let utls = optional_non_empty(&options, "utls")?.unwrap_or("hellochrome_auto");
    let tls_profile = tls_profile_for_utls(utls)?.to_owned();
    let cert = optional_non_empty(&options, "cert")?.map(ToOwned::to_owned);
    let cert_domain = optional_non_empty(&options, "cert-domain")?.map(ToOwned::to_owned);

    Ok(WebTunnelBridgeConfig {
        url: raw_url.to_owned(),
        version,
        addr,
        servername,
        utls: utls.to_owned(),
        tls_profile,
        http_host: host.to_owned(),
        secret_path: url.path().to_owned(),
        cert,
        cert_domain,
        protect_path: ripdpi_subprocess_protect::protect_path_from_env(),
    })
}

fn parse_options(tokens: &[&str]) -> Result<BTreeMap<String, String>, BridgeLineError> {
    let mut options = BTreeMap::new();
    for token in tokens {
        let delimiter = token.find('=').ok_or_else(|| BridgeLineError::MalformedOption((*token).to_owned()))?;
        let key = &token[..delimiter];
        let value = &token[delimiter + 1..];
        if key.is_empty() {
            return Err(BridgeLineError::MalformedOption((*token).to_owned()));
        }
        options.insert(key.to_owned(), value.to_owned());
    }
    Ok(options)
}

fn required_option<'a>(options: &'a BTreeMap<String, String>, key: &'static str) -> Result<&'a str, BridgeLineError> {
    optional_non_empty(options, key)?.ok_or(BridgeLineError::MissingUrl)
}

fn optional_non_empty<'a>(
    options: &'a BTreeMap<String, String>,
    key: &'static str,
) -> Result<Option<&'a str>, BridgeLineError> {
    options.get(key).map(|value| non_empty(value, key)).transpose()
}

fn non_empty<'a>(value: &'a str, key: &'static str) -> Result<&'a str, BridgeLineError> {
    if value.is_empty() { Err(BridgeLineError::EmptyValue(key)) } else { Ok(value) }
}

fn validate_endpoint(endpoint: &str) -> Result<(), BridgeLineError> {
    let Some((host, port)) = endpoint.rsplit_once(':') else {
        return Err(BridgeLineError::InvalidEndpoint);
    };
    if !host.starts_with('[') && host.contains(':') {
        return Err(BridgeLineError::InvalidEndpoint);
    }
    if host.is_empty() || port.parse::<u16>().ok().filter(|value| *value > 0).is_none() {
        return Err(BridgeLineError::InvalidEndpoint);
    }
    Ok(())
}

fn default_addr(host: &str, port: u16) -> String {
    if host.contains(':') && !host.starts_with('[') { format!("[{host}]:{port}") } else { format!("{host}:{port}") }
}

fn tls_profile_for_utls(utls: &str) -> Result<&'static str, BridgeLineError> {
    match utls.to_ascii_lowercase().as_str() {
        "hellochrome_auto" | "hellochrome_58" | "hellochrome_62" | "hellochrome_70" | "hellochrome_72"
        | "hellochrome_83" | "hellochrome_87" | "hellochrome_96" | "hellochrome_100" | "hellochrome_102" => {
            Ok("chrome_stable")
        }
        "hellofirefox_auto" | "hellofirefox_55" | "hellofirefox_56" | "hellofirefox_63" | "hellofirefox_65"
        | "hellofirefox_99" | "hellofirefox_102" | "hellofirefox_105" => Ok("firefox_stable"),
        "helloedge_auto" | "helloedge_85" | "helloedge_106" => Ok("edge_stable"),
        "hellosafari_auto" | "hellosafari_16_0" | "helloios_auto" | "helloios_11_1" | "helloios_12_1"
        | "helloios_13" | "helloios_14" => Ok("safari_stable"),
        "none"
        | "hellogolang"
        | "hellorandomized"
        | "hellorandomizedalpn"
        | "hellorandomizednoalpn"
        | "helloandroid_11"
        | "hello360_auto"
        | "hello360_7_5"
        | "hello360_11_0"
        | "helloqq_auto"
        | "helloqq_11_1" => Err(BridgeLineError::UnsupportedUtls(utls.to_owned())),
        _ => Err(BridgeLineError::UnsupportedUtls(utls.to_owned())),
    }
}

#[cfg(test)]
mod bare_ipv6_rejection_tests {
    use super::validate_endpoint;

    /// Regression test (audit H4 siblings): a bare IPv6 endpoint must be
    /// rejected instead of being silently accepted with a corrupted host.
    #[test]
    fn validate_endpoint_rejects_bare_ipv6_endpoint() {
        assert!(validate_endpoint("2001:db8::1").is_err(), "bare IPv6 without port must be rejected");
        assert!(validate_endpoint("2001:db8::1:443").is_err(), "unbracketed IPv6 with port must be rejected");
    }

    #[test]
    fn validate_endpoint_accepts_bracketed_ipv6_and_domain() {
        assert!(validate_endpoint("[2001:db8::1]:443").is_ok(), "bracketed IPv6 must be accepted");
        assert!(validate_endpoint("example.com:443").is_ok(), "domain endpoint must be accepted");
    }
}
