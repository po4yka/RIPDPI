use jni::Env;
use jni::objects::JString;

use crate::config::{TunnelLogContext, config_from_payload, parse_tunnel_config_json, sanitize_log_context};

pub(crate) struct ParsedSessionConfig {
    pub(crate) config: ripdpi_tunnel_config::Config,
    pub(crate) log_context: Option<TunnelLogContext>,
}

pub(crate) fn parse_session_config(env: &mut Env<'_>, config_json: JString) -> Result<ParsedSessionConfig, String> {
    let json = config_json.try_to_string(env).map_err(|_| "Invalid tunnel config payload".to_string())?;
    let payload = parse_tunnel_config_json(&json)?;
    let log_context = sanitize_log_context(payload.log_context.clone());
    let config = config_from_payload(payload)?;

    Ok(ParsedSessionConfig { config, log_context })
}

pub(crate) fn validate_tun_fd(tun_fd: jni::sys::jint) -> Result<(), &'static str> {
    if tun_fd < 0 { Err("Invalid TUN file descriptor") } else { Ok(()) }
}
