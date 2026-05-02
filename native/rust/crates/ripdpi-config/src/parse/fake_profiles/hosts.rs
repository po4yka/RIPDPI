use std::net::IpAddr;

use crate::ConfigError;

pub(crate) fn lower_host_char(ch: char) -> Option<char> {
    if ch.is_ascii_uppercase() {
        Some(ch.to_ascii_lowercase())
    } else if ('-'..='9').contains(&ch) || ch.is_ascii_lowercase() {
        Some(ch)
    } else {
        None
    }
}

fn host_template_char(ch: char) -> Option<char> {
    match ch {
        '.' => Some('.'),
        _ => lower_host_char(ch),
    }
}

fn normalize_domain_host(spec: &str, option: &str) -> Result<String, ConfigError> {
    let trimmed = spec.trim().trim_end_matches('.');
    if trimmed.is_empty() {
        return Err(ConfigError::invalid(option, Some(spec)));
    }
    if trimmed.contains(':') || trimmed.parse::<IpAddr>().is_ok() {
        return Err(ConfigError::invalid(option, Some(spec)));
    }

    let mut normalized = String::with_capacity(trimmed.len());
    for ch in trimmed.chars() {
        let Some(lower) = host_template_char(ch) else {
            return Err(ConfigError::invalid(option, Some(spec)));
        };
        normalized.push(lower);
    }

    if normalized.starts_with('.') || normalized.ends_with('.') || normalized.contains("..") {
        return Err(ConfigError::invalid(option, Some(spec)));
    }
    for label in normalized.split('.') {
        if label.is_empty() || label.starts_with('-') || label.ends_with('-') {
            return Err(ConfigError::invalid(option, Some(spec)));
        }
    }
    Ok(normalized)
}

pub fn normalize_fake_host_template(spec: &str) -> Result<String, ConfigError> {
    normalize_domain_host(spec, "hostfake-template")
}

pub fn normalize_quic_fake_host(spec: &str) -> Result<String, ConfigError> {
    normalize_domain_host(spec, "fake-quic-host")
}
