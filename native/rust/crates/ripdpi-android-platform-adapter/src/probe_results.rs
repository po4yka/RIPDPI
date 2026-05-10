use jni::objects::JString;
use jni::sys::jstring;
use jni::{Env, EnvUnowned, Outcome};
use ripdpi_runtime_strategy::strategy_evolver::{apply_global_probe_results, ProbeResult};

pub fn inject_entry(mut env: EnvUnowned<'_>, results_json: JString<'_>) -> jstring {
    match env
        .with_env(|env| -> jni::errors::Result<jstring> {
            let payload = results_json.mutf8_chars(env)?.to_str().into_owned();
            let result = inject_probe_results_json(&payload).map_err(|error| error.to_string());
            error_to_nullable_jstring(env, result)
        })
        .into_outcome()
    {
        Outcome::Ok(value) => value,
        _ => std::ptr::null_mut(),
    }
}

fn inject_probe_results_json(payload: &str) -> Result<(), serde_json::Error> {
    let results = serde_json::from_str::<Vec<ProbeResult>>(payload)?;
    apply_global_probe_results(&results);
    Ok(())
}

fn error_to_nullable_jstring(env: &mut Env<'_>, result: Result<(), String>) -> jni::errors::Result<jstring> {
    match result {
        Ok(()) => Ok(std::ptr::null_mut()),
        Err(error) => Ok(env.new_string(error)?.into_raw()),
    }
}
