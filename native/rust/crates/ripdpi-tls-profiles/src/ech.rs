use thiserror::Error;

/// Outbound Encrypted ClientHello configuration supplied by resolver/bootstrap policy.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OutboundEchConfig {
    pub public_name: String,
    pub config_list: Vec<u8>,
}

impl OutboundEchConfig {
    pub fn new(public_name: impl Into<String>, config_list: Vec<u8>) -> Result<Self, EchConfigError> {
        let public_name = public_name.into();
        if public_name.trim().is_empty() {
            return Err(EchConfigError::MissingPublicName);
        }
        if config_list.len() < 2 {
            return Err(EchConfigError::MalformedConfigList);
        }
        let declared_len = u16::from_be_bytes([config_list[0], config_list[1]]) as usize;
        if declared_len == 0 || declared_len + 2 != config_list.len() {
            return Err(EchConfigError::MalformedConfigList);
        }
        Ok(Self { public_name, config_list })
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OutboundEchBackend {
    Rustls,
    Boring,
}

#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum EchConfigError {
    #[error("ECH public name is required")]
    MissingPublicName,
    #[error("ECHConfigList must be a non-empty length-prefixed vector")]
    MalformedConfigList,
}

#[derive(Debug, Error, Clone, PartialEq, Eq)]
pub enum EchOutboundError {
    #[error("ECH config rejected by outbound TLS backend: {0}")]
    ConfigRejected(String),
    #[error("ECH retry required with server-provided retry config")]
    RetryRequired,
    #[error("ECH is not supported by {backend:?} outbound TLS backend")]
    UnsupportedBackend { backend: OutboundEchBackend },
}

pub fn require_ech_backend_support(
    backend: OutboundEchBackend,
    config: Option<&OutboundEchConfig>,
) -> Result<(), EchOutboundError> {
    if config.is_none() {
        return Ok(());
    }
    match backend {
        OutboundEchBackend::Rustls => Ok(()),
        OutboundEchBackend::Boring => Ok(()),
    }
}

pub fn configure_boring_ech(
    config: &mut boring::ssl::ConnectConfiguration,
    ech_config: Option<&OutboundEchConfig>,
) -> Result<(), EchOutboundError> {
    if let Some(ech_config) = ech_config {
        config
            .set_ech_config_list(&ech_config.config_list)
            .map_err(|error| EchOutboundError::ConfigRejected(error.to_string()))?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    const BORING_ECH_CONFIG_LIST: &[u8] = &[
        0x00, 0x3e, 0xfe, 0x0d, 0x00, 0x3a, 0x00, 0x00, 0x20, 0x00, 0x20, 0xbb, 0x2f, 0x29, 0xe3, 0xe3, 0x05, 0x7e,
        0x04, 0x19, 0xd5, 0x2f, 0xc5, 0xf4, 0x41, 0x18, 0x77, 0x6f, 0x8d, 0xb6, 0x1c, 0xea, 0x4f, 0xdf, 0x76, 0x07,
        0x9b, 0x93, 0x60, 0x6c, 0x5a, 0x62, 0x48, 0x00, 0x08, 0x00, 0x01, 0x00, 0x01, 0x00, 0x01, 0x00, 0x03, 0x00,
        0x07, 0x65, 0x63, 0x68, 0x2e, 0x63, 0x6f, 0x6d, 0x00, 0x00,
    ];

    fn config() -> OutboundEchConfig {
        OutboundEchConfig::new("ech.com", BORING_ECH_CONFIG_LIST.to_vec()).expect("config")
    }

    #[test]
    fn ech_config_list_must_be_length_prefixed() {
        assert_eq!(
            OutboundEchConfig::new("public.example", vec![0, 4, 1, 2, 3]).expect_err("length mismatch"),
            EchConfigError::MalformedConfigList
        );
        assert_eq!(
            OutboundEchConfig::new("", vec![0, 3, 1, 2, 3]).expect_err("missing public name"),
            EchConfigError::MissingPublicName
        );
    }

    #[test]
    fn boring_backend_accepts_configured_ech_contract() {
        require_ech_backend_support(OutboundEchBackend::Boring, Some(&config())).expect("boring contract");
    }

    #[test]
    fn rustls_backend_accepts_configured_ech_contract() {
        require_ech_backend_support(OutboundEchBackend::Rustls, Some(&config())).expect("rustls contract");
    }

    #[test]
    fn boring_ech_config_is_applied_to_connect_configuration() {
        let connector = crate::build_connector("native_default", true).expect("connector");
        let mut tls_config = connector.configure().expect("connect config");
        configure_boring_ech(&mut tls_config, Some(&config())).expect("configured");
    }
}
