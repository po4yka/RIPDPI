use android_support::{throw_illegal_argument, throw_illegal_state};
use jni::objects::JString;
use jni::sys::jlong;
use jni::JNIEnv;
use ripdpi_monitor::{EngineScanRequestWire, ScanRequest, DIAGNOSTICS_ENGINE_SCHEMA_VERSION};

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
    match serde_json::from_str::<EngineScanRequestWire>(request_json) {
        Ok(request) => Ok(request),
        Err(_) => serde_json::from_str::<ScanRequest>(request_json)
            .map(upgrade_legacy_scan_request)
            .map_err(|err| format!("Invalid diagnostics request: {err}")),
    }
}

fn upgrade_legacy_scan_request(request: ScanRequest) -> EngineScanRequestWire {
    EngineScanRequestWire {
        schema_version: DIAGNOSTICS_ENGINE_SCHEMA_VERSION,
        profile_id: request.profile_id,
        display_name: request.display_name,
        path_mode: request.path_mode,
        kind: request.kind,
        family: request.family,
        region_tag: request.region_tag,
        pack_refs: request.pack_refs,
        proxy_host: request.proxy_host,
        proxy_port: request.proxy_port,
        probe_tasks: request.probe_tasks,
        domain_targets: request.domain_targets,
        dns_targets: request.dns_targets,
        tcp_targets: request.tcp_targets,
        quic_targets: request.quic_targets,
        service_targets: request.service_targets,
        circumvention_targets: request.circumvention_targets,
        throughput_targets: request.throughput_targets,
        whitelist_sni: request.whitelist_sni,
        telegram_target: request.telegram_target,
        strategy_probe: request.strategy_probe,
        network_snapshot: request.network_snapshot,
        native_log_level: None,
        log_context: None,
    }
}
