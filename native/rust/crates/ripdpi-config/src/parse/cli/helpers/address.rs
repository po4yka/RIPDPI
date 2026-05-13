use std::net::IpAddr;
use std::str::FromStr;

use crate::ConfigError;

pub(crate) fn parse_numeric_addr(spec: &str) -> Result<(IpAddr, Option<u16>), ConfigError> {
    let (host, port) = if let Some(rest) = spec.strip_prefix('[') {
        let end = rest.find(']').ok_or_else(|| ConfigError::invalid("address", Some(spec)))?;
        let host = &rest[..end];
        let suffix = &rest[end + 1..];
        let port = if let Some(port_str) = suffix.strip_prefix(':') {
            Some(port_str.parse::<u16>().map_err(|_| ConfigError::invalid("address", Some(spec)))?)
        } else if suffix.is_empty() {
            None
        } else {
            return Err(ConfigError::invalid("address", Some(spec)));
        };
        (host, port)
    } else {
        let colon_count = spec.bytes().filter(|&byte| byte == b':').count();
        if colon_count == 1 {
            match spec.rsplit_once(':') {
                Some((host, port_str)) if !port_str.is_empty() && port_str.as_bytes()[0].is_ascii_digit() => {
                    let port = port_str.parse::<u16>().map_err(|_| ConfigError::invalid("address", Some(spec)))?;
                    (host, Some(port))
                }
                _ => (spec, None),
            }
        } else {
            (spec, None)
        }
    };
    let ip = IpAddr::from_str(host).map_err(|_| ConfigError::invalid("address", Some(spec)))?;
    Ok((ip, port))
}
