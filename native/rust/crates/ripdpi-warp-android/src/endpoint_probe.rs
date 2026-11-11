use jni::objects::{JObject, JString};
use jni::{EnvUnowned, Outcome};
use ripdpi_warp_core::WarpEndpointProbeRequest;

use crate::vpn_protect;

pub(crate) fn probe(mut env: EnvUnowned<'_>, request_json: JString<'_>, controller: JObject<'_>) -> jni::sys::jstring {
    let prepared = env
        .with_env(|env| -> jni::errors::Result<_> {
            if request_json.is_null() || controller.is_null() {
                return Ok(None);
            }
            let request_json: String = request_json.mutf8_chars(env)?.to_str().into_owned();
            let Ok(request) = serde_json::from_str::<WarpEndpointProbeRequest>(&request_json) else {
                return Ok(None);
            };
            let vm = env.get_java_vm()?;
            let platform = vpn_protect::probe_platform(&vm, env.new_global_ref(controller)?);
            Ok(Some((request, platform)))
        })
        .into_outcome();
    let (request, platform) = match prepared {
        Outcome::Ok(Some(prepared)) => prepared,
        Outcome::Ok(None) => return std::ptr::null_mut(),
        _ => {
            clear_pending(&mut env);
            return std::ptr::null_mut();
        }
    };
    // No Env scope spans block_on. The owned VM/global reference remains live
    // through private-runtime cleanup, including timeout and error paths.
    let payload = tokio::runtime::Builder::new_current_thread()
        .enable_all()
        .build()
        .ok()
        .and_then(|runtime| {
            let result = runtime.block_on(ripdpi_warp_core::probe_endpoint_with_platform(request, &platform)).ok();
            // Timed-out DNS lookup tasks may still block; do not wait for them here.
            runtime.shutdown_background();
            result
        })
        .and_then(|result| serde_json::to_string(&result).ok());
    let Some(payload) = payload else {
        return std::ptr::null_mut();
    };
    match env.with_env(|env| -> jni::errors::Result<_> { Ok(env.new_string(payload)?.into_raw()) }).into_outcome() {
        Outcome::Ok(value) => value,
        _ => {
            clear_pending(&mut env);
            std::ptr::null_mut()
        }
    }
}

fn clear_pending(env: &mut EnvUnowned<'_>) {
    let _ = env.with_env(|env| -> jni::errors::Result<()> {
        if env.exception_check() {
            env.exception_clear();
        }
        Ok(())
    });
}
