use jni::objects::JString;
use jni::{EnvUnowned, Outcome};
use ripdpi_warp_core::WarpEndpointProbeRequest;

use crate::vpn_protect;

pub(crate) fn probe(mut env: EnvUnowned<'_>, request_json: JString<'_>) -> jni::sys::jstring {
    match env
        .with_env(move |env| -> jni::errors::Result<jni::sys::jstring> {
            let request_json: String = request_json.mutf8_chars(env)?.to_str().into_owned();
            let Ok(request) = serde_json::from_str::<WarpEndpointProbeRequest>(&request_json) else {
                return Ok(std::ptr::null_mut());
            };
            let payload = tokio::runtime::Builder::new_current_thread()
                .enable_all()
                .build()
                .ok()
                .and_then(|runtime| {
                    let platform = vpn_protect::warp_platform();
                    runtime.block_on(ripdpi_warp_core::probe_endpoint_with_platform(request, &platform)).ok()
                })
                .and_then(|result| serde_json::to_string(&result).ok());
            match payload {
                Some(value) => Ok(env.new_string(value)?.into_raw()),
                None => Ok(std::ptr::null_mut()),
            }
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        _ => std::ptr::null_mut(),
    }
}
