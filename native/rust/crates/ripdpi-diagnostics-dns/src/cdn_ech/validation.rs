use crate::cdn_ech::source::EchSourceError;

/// Parse the complete ECHConfigList and require at least one supported entry.
pub fn validate_ech_config_list_bytes(bytes: &[u8]) -> Result<usize, EchSourceError> {
    if bytes.len() < 4 {
        return Err(EchSourceError::InvalidConfig(format!("ECHConfigList too short: {} bytes", bytes.len())));
    }
    let declared = u16::from_be_bytes([bytes[0], bytes[1]]) as usize;
    if declared + 2 != bytes.len() {
        return Err(EchSourceError::InvalidConfig(format!(
            "ECHConfigList length prefix {declared} does not match buffer length {}",
            bytes.len()
        )));
    }
    let config = ripdpi_dns_resolver::parse_ech_config_list(bytes)
        .map_err(|error| EchSourceError::InvalidConfig(error.to_string()))?;
    if config.supported_config_list().is_none() {
        return Err(EchSourceError::InvalidConfig("ECHConfigList has no supported entry".to_string()));
    }
    Ok(bytes.len())
}
