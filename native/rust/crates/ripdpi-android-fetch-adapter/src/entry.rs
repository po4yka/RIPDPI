use jni::EnvUnowned;
use jni::objects::JString;
use jni::sys::jstring;

use crate::entry_dispatch::json_entry;
use crate::{connect_ech, execute};

pub fn execute_entry(env: EnvUnowned<'_>, request_json: JString<'_>) -> jstring {
    json_entry(env, request_json, execute)
}

pub fn connect_ech_entry(env: EnvUnowned<'_>, request_json: JString<'_>) -> jstring {
    json_entry(env, request_json, connect_ech)
}
