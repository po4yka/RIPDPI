use jni::objects::JString;
use jni::sys::jstring;
use jni::{EnvUnowned, Outcome};
use std::path::Path;

mod payload;

pub fn apply_entry(
    mut env: EnvUnowned<'_>,
    manifest_json: JString<'_>,
    priors_base64: JString<'_>,
    watermark_path: JString<'_>,
) -> jstring {
    match env
        .with_env(move |env| -> jni::errors::Result<jstring> {
            let manifest_bytes = manifest_json.mutf8_chars(env)?.to_str().into_owned().into_bytes();
            let priors_b64 = priors_base64.mutf8_chars(env)?.to_str().into_owned();
            let path = watermark_path.mutf8_chars(env)?.to_str().into_owned();
            let payload = payload::from_inputs(&manifest_bytes, &priors_b64, Path::new(&path));
            Ok(env.new_string(payload)?.into_raw())
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        _ => std::ptr::null_mut(),
    }
}
