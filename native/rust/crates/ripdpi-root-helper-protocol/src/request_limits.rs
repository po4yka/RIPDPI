use std::io;

use serde::{Deserialize, Serialize};
use serde_json::Value;

use crate::scm_rights::MAX_MESSAGE_BYTES;
use crate::{
    CMD_SEND_FAKE_TCP, CMD_SEND_MULTI_DISORDER_TCP, CMD_SEND_ORDERED_TCP_SEGMENTS, FakeTcpParams, HelperRequest,
    MultiDisorderParams, OrderedTcpSegmentsParams,
};

pub(crate) const MAX_BULK_REQUEST_BYTES: usize = 1024 * 1024;
const MAX_TCP_PAYLOAD_BYTES: usize = u16::MAX as usize;
const MAX_TCP_AGGREGATE_BYTES: usize = 3 * MAX_TCP_PAYLOAD_BYTES;
// Current fake-family emitters use at most four segments; also bound metadata.
const MAX_ORDERED_TCP_SEGMENTS: usize = 64;

#[derive(Clone, Copy, Deserialize, Serialize)]
struct BulkTcpLimits {
    max_message_bytes: usize,
    max_payload_bytes: usize,
    max_aggregate_bytes: usize,
    max_segments: usize,
    #[serde(default)]
    multidisorder: bool,
}

const BULK_TCP_LIMITS: BulkTcpLimits = BulkTcpLimits {
    max_message_bytes: MAX_BULK_REQUEST_BYTES,
    max_payload_bytes: MAX_TCP_PAYLOAD_BYTES,
    max_aggregate_bytes: MAX_TCP_AGGREGATE_BYTES,
    max_segments: MAX_ORDERED_TCP_SEGMENTS,
    multidisorder: true,
};

/// Additive preflight metadata; the v4 parameter representation stays unchanged.
pub fn bulk_tcp_preflight_data() -> Value {
    serde_json::json!({ "bulk_tcp": BULK_TCP_LIMITS })
}

/// Check the peer's advertised bulk limits before connecting to send a command.
/// A legacy preflight without limits permits control-sized requests only.
pub fn validate_request_with_peer(request: &HelperRequest, encoded_len: usize, preflight: &Value) -> io::Result<()> {
    validate_frame_limit(request, encoded_len, BULK_TCP_LIMITS)?;
    if !matches!(
        request.command.as_str(),
        CMD_SEND_FAKE_TCP | CMD_SEND_ORDERED_TCP_SEGMENTS | CMD_SEND_MULTI_DISORDER_TCP
    ) {
        return Ok(());
    }
    let limits = match preflight.get("bulk_tcp") {
        Some(value) => serde_json::from_value::<BulkTcpLimits>(value.clone())
            .map_err(|error| invalid(format!("invalid root-helper bulk TCP limits: {error}")))?,
        None if encoded_len <= MAX_MESSAGE_BYTES => BULK_TCP_LIMITS,
        None => return Err(invalid("root helper did not advertise bulk TCP request limits")),
    };
    if request.command == CMD_SEND_MULTI_DISORDER_TCP && encoded_len > MAX_MESSAGE_BYTES && !limits.multidisorder {
        return Err(invalid("root helper did not advertise bulk multidisorder requests"));
    }
    validate_request_limits(request, encoded_len, limits)
}

pub(crate) fn validate_request_message(json: &[u8]) -> io::Result<()> {
    let request = decode_request(json)?;
    validate_request_limits(&request, json.len(), BULK_TCP_LIMITS)
}

pub(crate) fn validate_request_envelope(json: &[u8]) -> io::Result<()> {
    let request = decode_request(json)?;
    validate_frame_limit(&request, json.len(), BULK_TCP_LIMITS)
}

fn decode_request(json: &[u8]) -> io::Result<HelperRequest> {
    if json.len() > MAX_BULK_REQUEST_BYTES {
        return Err(invalid("root-helper request exceeds the bulk frame limit"));
    }
    serde_json::from_slice(json).map_err(|error| invalid(error.to_string()))
}

fn validate_frame_limit(request: &HelperRequest, encoded_len: usize, limits: BulkTcpLimits) -> io::Result<()> {
    let bulk = matches!(
        request.command.as_str(),
        CMD_SEND_FAKE_TCP | CMD_SEND_ORDERED_TCP_SEGMENTS | CMD_SEND_MULTI_DISORDER_TCP
    );
    let frame_limit = if bulk { limits.max_message_bytes.min(MAX_BULK_REQUEST_BYTES) } else { MAX_MESSAGE_BYTES };
    if encoded_len > frame_limit {
        return Err(invalid(format!("root-helper request exceeds its {frame_limit}-byte command limit")));
    }
    Ok(())
}

fn validate_request_limits(request: &HelperRequest, encoded_len: usize, limits: BulkTcpLimits) -> io::Result<()> {
    validate_frame_limit(request, encoded_len, limits)?;
    match request.command.as_str() {
        CMD_SEND_FAKE_TCP => {
            let params: FakeTcpParams =
                serde_json::from_value(request.params.clone()).map_err(|error| invalid(error.to_string()))?;
            validate_payload_lengths(
                [
                    params.original_prefix.len(),
                    params.fake_prefix.len(),
                    params.secondary_fake_prefix.as_ref().map_or(0, Vec::len),
                ],
                limits,
            )
        }
        CMD_SEND_ORDERED_TCP_SEGMENTS => {
            let params: OrderedTcpSegmentsParams =
                serde_json::from_value(request.params.clone()).map_err(|error| invalid(error.to_string()))?;
            if params.segments.len() > limits.max_segments.min(MAX_ORDERED_TCP_SEGMENTS) {
                return Err(invalid("root-helper ordered TCP segment count exceeds its limit"));
            }
            if params.original_payload_len > limits.max_payload_bytes.min(MAX_TCP_PAYLOAD_BYTES) {
                return Err(invalid("root-helper original TCP payload exceeds its limit"));
            }
            validate_payload_lengths(params.segments.iter().map(|segment| segment.payload.len()), limits)
        }
        CMD_SEND_MULTI_DISORDER_TCP => {
            let params: MultiDisorderParams =
                serde_json::from_value(request.params.clone()).map_err(|error| invalid(error.to_string()))?;
            validate_payload_lengths([params.payload.len()], limits)?;
            if !(3..=limits.max_segments.min(MAX_ORDERED_TCP_SEGMENTS)).contains(&params.segments.len()) {
                return Err(invalid("root-helper multidisorder segment count must be between 3 and its limit"));
            }
            let mut cursor = 0;
            for segment in &params.segments {
                if segment.start != cursor || segment.end <= segment.start || segment.end > params.payload.len() {
                    return Err(invalid("invalid root-helper multidisorder payload segments"));
                }
                cursor = segment.end;
            }
            if cursor != params.payload.len() {
                return Err(invalid("root-helper multidisorder segments must cover the full payload"));
            }
            Ok(())
        }
        _ => Ok(()),
    }
}

fn validate_payload_lengths(lengths: impl IntoIterator<Item = usize>, limits: BulkTcpLimits) -> io::Result<()> {
    let mut total = 0usize;
    for len in lengths {
        if len > limits.max_payload_bytes.min(MAX_TCP_PAYLOAD_BYTES) {
            return Err(invalid("root-helper TCP payload span exceeds its limit"));
        }
        total = total.checked_add(len).ok_or_else(|| invalid("root-helper TCP payload length overflow"))?;
    }
    if total > limits.max_aggregate_bytes.min(MAX_TCP_AGGREGATE_BYTES) {
        return Err(invalid("root-helper aggregate TCP payload exceeds its limit"));
    }
    Ok(())
}

fn invalid(message: impl Into<String>) -> io::Error {
    io::Error::new(io::ErrorKind::InvalidInput, message.into())
}
