use android_support::{throw_illegal_argument_env, throw_illegal_state_env};
use jni::Env;
use jni::objects::JString;
use jni::sys::jlong;
use ripdpi_diagnostics_contracts::EngineScanRequestWire;
use ripdpi_monitor_engine::MAX_REQUEST_WIRE_BYTES;

use super::registry::diagnostics_session;

pub(crate) fn start_diagnostics_scan(env: &mut Env<'_>, handle: jlong, request_json: JString, session_id: JString) {
    let Some(session) = diagnostics_session(env, handle) else {
        return;
    };
    let Ok(request_json) = request_json.try_to_string(env) else {
        throw_illegal_argument_env(env, "Invalid diagnostics request JSON");
        return;
    };
    let Ok(session_id) = session_id.try_to_string(env) else {
        throw_illegal_argument_env(env, "Invalid diagnostics session id");
        return;
    };
    let request = match decode_scan_request(&request_json) {
        Ok(request) => request,
        Err(err) => {
            throw_illegal_argument_env(env, err);
            return;
        }
    };
    if let Err(err) = session.start_scan(session_id, request) {
        throw_illegal_state_env(env, err);
    }
}

pub(crate) fn cancel_diagnostics_scan(env: &mut Env<'_>, handle: jlong) {
    let Some(session) = diagnostics_session(env, handle) else {
        return;
    };
    session.cancel_scan();
}

fn decode_scan_request(request_json: &str) -> Result<EngineScanRequestWire, String> {
    // Reject oversized payloads before serde walks them: the wire cap is the
    // first line of defense, not a post-parse afterthought.
    if request_json.len() > MAX_REQUEST_WIRE_BYTES {
        return Err(format!("diagnostics request size exceeds {MAX_REQUEST_WIRE_BYTES} bytes"));
    }
    serde_json::from_str::<EngineScanRequestWire>(request_json)
        .map_err(|err| format!("Invalid diagnostics request: {err}"))
}

#[cfg(test)]
mod size_cap_tests {
    use super::*;

    #[test]
    fn oversized_requests_are_rejected_before_serde_parses_them() {
        let oversized = " ".repeat(MAX_REQUEST_WIRE_BYTES + 1);
        let err = decode_scan_request(&oversized).expect_err("oversized request must be rejected");
        assert!(err.contains("exceeds"), "wire-size cap must fire before serde, got: {err}",);
    }
}
