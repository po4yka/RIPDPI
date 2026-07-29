use android_support::throw_runtime_exception_env;
use jni::Env;
use jni::sys::jstring;
use ripdpi_android_bridge_support::JniProxyError;

pub(super) fn poll_json(env: &mut Env<'_>, telemetry: Result<String, JniProxyError>) -> jstring {
    let telemetry = match telemetry {
        Ok(value) => value,
        Err(err) => {
            err.throw(env);
            return std::ptr::null_mut();
        }
    };
    match env.new_string(telemetry) {
        Ok(value) => value.into_raw(),
        Err(err) => {
            throw_runtime_exception_env(env, err.to_string());
            std::ptr::null_mut()
        }
    }
}
