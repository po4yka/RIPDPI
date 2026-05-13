use std::io;

use jni::objects::JString;
use jni::sys::jstring;
use jni::{EnvUnowned, Outcome};

pub(crate) fn json_entry(
    mut env: EnvUnowned<'_>,
    request_json: JString<'_>,
    handler: fn(&str) -> io::Result<String>,
) -> jstring {
    match env
        .with_env(move |env| -> jni::errors::Result<jstring> {
            let request_json: String = request_json.mutf8_chars(env)?.to_str().into_owned();
            let payload = handler(&request_json).unwrap_or_else(|error| {
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
