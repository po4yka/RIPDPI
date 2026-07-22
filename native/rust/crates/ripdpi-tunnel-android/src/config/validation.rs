use crate::config::limits::validate_limits;
use crate::config::payload::{TUNNEL_JNI_CONFIG_SCHEMA_VERSION, TunnelConfigPayload};

trait BlankCheck {
    fn is_blank(&self) -> bool;
}

impl BlankCheck for String {
    fn is_blank(&self) -> bool {
        self.trim().is_empty()
    }
}

pub(crate) fn validate_payload(payload: &TunnelConfigPayload) -> Result<(), String> {
    if payload.schema_version != TUNNEL_JNI_CONFIG_SCHEMA_VERSION {
        return Err(format!(
            "Unsupported tunnel config schemaVersion: {}; expected {TUNNEL_JNI_CONFIG_SCHEMA_VERSION}",
            payload.schema_version
        ));
    }
    if payload.socks5_address.is_blank() {
        return Err("socks5Address must not be blank".to_string());
    }
    if payload.tunnel_name.is_blank() {
        return Err("tunnelName must not be blank".to_string());
    }
    if payload.socks5_port == 0 {
        return Err("socks5Port must be non-zero".to_string());
    }
    if let Some(port) = payload.mapdns_port
        && port == 0
    {
        return Err("mapdnsPort must be non-zero".to_string());
    }
    validate_limits(payload)
}
