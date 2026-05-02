use jni::objects::JString;
use jni::sys::jstring;
use jni::{EnvUnowned, Outcome};

use crate::owned_tls_http::execute;

pub(super) fn execute_entry(mut env: EnvUnowned<'_>, request_json: JString<'_>) -> jstring {
    match env
        .with_env(move |env| -> jni::errors::Result<jstring> {
            let request_json: String = request_json.mutf8_chars(env)?.to_str().into_owned();
            let payload = execute(&request_json).unwrap_or_else(|error| {
                serde_json::json!({
                    "error": error.to_string(),
                })
                .to_string()
            });
            Ok(env.new_string(payload)?.into_raw())
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        _ => std::ptr::null_mut(),
    }
}
