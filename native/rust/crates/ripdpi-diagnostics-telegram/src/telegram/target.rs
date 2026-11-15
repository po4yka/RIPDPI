use std::collections::HashSet;
use std::net::{IpAddr, SocketAddr};

use crate::http::parse_https_endpoint;
use crate::tls::make_server_name;
use crate::transport::TargetAddress;
use crate::types::TelegramTarget;

const MAX_MEDIA_URL_BYTES: usize = 2048;
const MAX_DC_ENDPOINTS: usize = 32;
const MAX_DC_LABEL_BYTES: usize = 64;
pub(super) const MAX_UPLOAD_BYTES: usize = 16 * 1024 * 1024;
pub(super) const MAX_TRANSFER_MS: u64 = 60_000;

/// Check all configured Telegram endpoints and budgets before scheduling a scan.
///
/// # Errors
///
/// Returns the first invalid Telegram target field, without echoing its contents.
pub fn validate_telegram_target(target: &TelegramTarget) -> Result<(), &'static str> {
    if target.media_url.len() > MAX_MEDIA_URL_BYTES || parse_https_endpoint(&target.media_url).is_err() {
        return Err("telegramTarget.mediaUrl must be a valid HTTPS URL with at most 2048 bytes");
    }
    if target.upload_port == 0
        || make_server_name(&target.upload_ip, &TargetAddress::Host(target.upload_ip.clone())).is_err()
        || target.upload_ip.contains(['[', ']'])
    {
        return Err("telegramTarget upload endpoint must use an IP or DNS name and a nonzero port");
    }
    if !(1..=MAX_UPLOAD_BYTES).contains(&target.upload_size_bytes) {
        return Err("telegramTarget.uploadSizeBytes must be between 1 and 16777216");
    }
    if !(1..=MAX_TRANSFER_MS).contains(&target.total_timeout_ms)
        || !(1..=target.total_timeout_ms).contains(&target.stall_timeout_ms)
    {
        return Err("telegramTarget timeout limits require 1 <= stallTimeoutMs <= totalTimeoutMs <= 60000");
    }
    if target.dc_endpoints.len() > MAX_DC_ENDPOINTS {
        return Err("telegramTarget.dcEndpoints count exceeds 32");
    }
    let mut addresses = HashSet::new();
    for endpoint in &target.dc_endpoints {
        let ip = endpoint.ip.parse::<IpAddr>().map_err(|_| "telegramTarget DC endpoint needs a numeric IP")?;
        if endpoint.port == 0 || !addresses.insert(SocketAddr::new(ip, endpoint.port)) {
            return Err("telegramTarget DC endpoints need nonzero ports and unique socket addresses");
        }
        if endpoint.label.trim().is_empty()
            || endpoint.label.len() > MAX_DC_LABEL_BYTES
            || endpoint.label.contains([':', '|'])
            || endpoint.label.chars().any(char::is_control)
        {
            return Err("telegramTarget DC label must be nonblank, bounded, and contain no evidence separators");
        }
    }
    Ok(())
}
