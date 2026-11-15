use std::io;

use serde_json::Value;

use crate::{CMD_RECV_ICMP_WRAPPED_UDP, scm_rights::MAX_MESSAGE_BYTES};

// JSON arrays use at most four bytes per octet; metadata has only bounded scalar fields.
pub(crate) const MAX_ICMP_RESPONSE_BYTES: usize = 4 * u16::MAX as usize + 1024;

pub(crate) fn response_limit(command: &str) -> usize {
    if command == CMD_RECV_ICMP_WRAPPED_UDP { MAX_ICMP_RESPONSE_BYTES } else { MAX_MESSAGE_BYTES }
}

pub(crate) fn validate_response_support(command: &str, preflight: &Value) -> io::Result<()> {
    if command == CMD_RECV_ICMP_WRAPPED_UDP
        && preflight
            .get("icmp_wrapped_udp_receive")
            .and_then(|value| value.get("max_response_bytes"))
            .and_then(Value::as_u64)
            .is_none_or(|limit| limit < MAX_ICMP_RESPONSE_BYTES as u64)
    {
        return Err(io::Error::new(
            io::ErrorKind::Unsupported,
            "root helper did not advertise complete ICMP receive responses",
        ));
    }
    Ok(())
}
