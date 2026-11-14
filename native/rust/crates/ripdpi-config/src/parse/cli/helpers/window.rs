use crate::{ConfigError, WsizeConfig};

pub(crate) fn parse_wsize(arg: &str, value: &str) -> Result<WsizeConfig, ConfigError> {
    let invalid = || ConfigError::invalid(arg, Some(value.to_string()));
    let (win_str, scale_str) = value.split_once(':').map_or((value, None), |(w, s)| (w, Some(s)));
    let window = win_str.parse::<u32>().map_err(|_| invalid())?;
    let scale = scale_str.map(|s| s.parse::<u8>().map_err(|_| invalid())).transpose()?;
    let config = WsizeConfig { window, scale };
    config.receive_buffer_size().ok_or_else(invalid)?;
    Ok(config)
}

pub(crate) fn parse_window_clamp(arg: &str, value: &str) -> Result<u32, ConfigError> {
    let clamp = value.parse::<u32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
    if !(1..=i32::MAX as u32).contains(&clamp) {
        return Err(ConfigError::invalid(arg, Some(value)));
    }
    Ok(clamp)
}
