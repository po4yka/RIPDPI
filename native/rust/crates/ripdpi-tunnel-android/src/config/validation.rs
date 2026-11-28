use crate::config::limits::validate_limits;
use crate::config::payload::{TUNNEL_JNI_CONFIG_SCHEMA_VERSION, TunnelConfigPayload};

pub(crate) fn validate_payload(payload: &TunnelConfigPayload) -> Result<(), String> {
    if payload.schema_version != TUNNEL_JNI_CONFIG_SCHEMA_VERSION {
        return Err(format!(
            "Unsupported tunnel config schemaVersion: {}; expected {TUNNEL_JNI_CONFIG_SCHEMA_VERSION}",
            payload.schema_version
        ));
    }
    let tcp_upstream = payload.socks5_unix_path.is_none();
    if tcp_upstream && payload.socks5_address.as_deref().is_some_and(|address| address.trim().is_empty()) {
        return Err("socks5Address must not be blank".to_string());
    }
    if payload.tunnel_name.trim().is_empty() {
        return Err("tunnelName must not be blank".to_string());
    }
    if tcp_upstream && payload.socks5_port.unwrap_or_default() == 0 {
        return Err("socks5Port must be non-zero".to_string());
    }
    if let Some(port) = payload.mapdns_port
        && port == 0
    {
        return Err("mapdnsPort must be non-zero".to_string());
    }
    validate_limits(payload)
}
