use crate::cdn_ech::source::EchSourceError;

/// Sanity-check raw ECHConfigList bytes before accepting them as fresh remote
/// config. We require a 2-byte length prefix that matches the rest of the
/// buffer and the first ECHConfig version equal to `0xfe0d`.
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
    if bytes[2] != 0xfe || bytes[3] != 0x0d {
        return Err(EchSourceError::InvalidConfig(format!(
            "unexpected ECHConfig version 0x{:02x}{:02x}, want 0xfe0d",
            bytes[2], bytes[3]
        )));
    }
    Ok(bytes.len())
}
