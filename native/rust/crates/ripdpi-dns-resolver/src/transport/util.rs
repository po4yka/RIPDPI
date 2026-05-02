use std::net::SocketAddr;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::types::EncryptedDnsError;

pub(crate) fn resolve_socket_addr(host: &str, port: u16) -> Result<SocketAddr, EncryptedDnsError> {
    std::net::ToSocketAddrs::to_socket_addrs(&(host, port))
        .map_err(|err| EncryptedDnsError::Request(err.to_string()))?
        .next()
        .ok_or_else(|| EncryptedDnsError::Request("no socket addresses resolved".to_string()))
}

pub(crate) fn unix_time_secs() -> u32 {
    SystemTime::now().duration_since(UNIX_EPOCH).unwrap_or_default().as_secs().try_into().unwrap_or(u32::MAX)
}

pub(crate) fn format_error_chain(error: &(dyn std::error::Error + 'static)) -> String {
    let mut message = error.to_string();
    let mut current = error.source();
    while let Some(source) = current {
        message.push_str(": ");
        message.push_str(&source.to_string());
        current = source.source();
    }
    message
}
