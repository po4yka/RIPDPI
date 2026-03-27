use android_support::{throw_illegal_argument, throw_illegal_state};
use jni::objects::JString;
use jni::sys::jlong;
use jni::JNIEnv;
use ripdpi_monitor::EngineScanRequestWire;

use super::registry::diagnostics_session;

pub(crate) fn start_diagnostics_scan(env: &mut JNIEnv, handle: jlong, request_json: JString, session_id: JString) {
    let Some(session) = diagnostics_session(env, handle) else {
        return;
    };
    let request_json = match env.get_string(&request_json) {
        Ok(value) => String::from(value),
        Err(_) => {
            throw_illegal_argument(env, "Invalid diagnostics request JSON");
            return;
        }
    };
    let session_id = match env.get_string(&session_id) {
        Ok(value) => String::from(value),
        Err(_) => {
            throw_illegal_argument(env, "Invalid diagnostics session id");
            return;
        }
    };
    let request = match decode_scan_request(&request_json) {
        Ok(request) => request,
        Err(err) => {
            throw_illegal_argument(env, err);
            return;
        }
    };
    if let Err(err) = session.start_scan(session_id, request) {
        throw_illegal_state(env, err);
    }
}

pub(crate) fn cancel_diagnostics_scan(env: &mut JNIEnv, handle: jlong) {
    let Some(session) = diagnostics_session(env, handle) else {
        return;
    };
    session.cancel_scan();
}

fn decode_scan_request(request_json: &str) -> Result<EngineScanRequestWire, String> {
    serde_json::from_str::<EngineScanRequestWire>(request_json)
        .map_err(|err| format!("Invalid diagnostics request: {err}"))
}
