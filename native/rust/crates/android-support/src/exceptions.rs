use jni::objects::{JString, JThrowable};
use jni::strings::JNIString;
use jni::sys::jstring;
use jni::{Env, EnvUnowned, Outcome};

pub fn throw_illegal_argument(env: &mut EnvUnowned<'_>, message: impl AsRef<str>) {
    throw_exception(env, "java/lang/IllegalArgumentException", "IllegalArgumentException", message);
}

pub fn throw_illegal_argument_env(env: &mut Env<'_>, message: impl AsRef<str>) {
    throw_exception_env(env, "java/lang/IllegalArgumentException", "IllegalArgumentException", message);
}

pub fn throw_illegal_state(env: &mut EnvUnowned<'_>, message: impl AsRef<str>) {
    throw_exception(env, "java/lang/IllegalStateException", "IllegalStateException", message);
}

pub fn throw_illegal_state_env(env: &mut Env<'_>, message: impl AsRef<str>) {
    throw_exception_env(env, "java/lang/IllegalStateException", "IllegalStateException", message);
}

pub fn throw_io_exception(env: &mut EnvUnowned<'_>, message: impl AsRef<str>) {
    throw_exception(env, "java/io/IOException", "IOException", message);
}

pub fn throw_io_exception_env(env: &mut Env<'_>, message: impl AsRef<str>) {
    throw_exception_env(env, "java/io/IOException", "IOException", message);
}

pub fn throw_runtime_exception(env: &mut EnvUnowned<'_>, message: impl AsRef<str>) {
    throw_exception(env, "java/lang/RuntimeException", "RuntimeException", message);
}

pub fn throw_runtime_exception_env(env: &mut Env<'_>, message: impl AsRef<str>) {
    throw_exception_env(env, "java/lang/RuntimeException", "RuntimeException", message);
}

/// Produce a user-safe error message, stripping internal details in release builds.
pub fn sanitize_error_message(detail: &str, user_message: &str) -> String {
    if cfg!(debug_assertions) {
        format!("{user_message}: {detail}")
    } else {
        user_message.to_string()
    }
}

pub fn describe_exception(env: &mut EnvUnowned<'_>) -> Option<String> {
    match env
        .with_env(|env| -> jni::errors::Result<Option<String>> {
            if !env.exception_check() {
                return Ok(None);
            }
            let Some(throwable) = env.exception_occurred() else {
                return Ok(None);
            };
            env.exception_clear();
            Ok(throwable_to_string(env, throwable))
        })
        .into_outcome()
    {
        Outcome::Ok(description) => description,
        Outcome::Err(err) => {
            log::error!("Failed to describe pending Java exception: {err}");
            None
        }
        Outcome::Panic(_) => {
            log::error!("Panic while describing pending Java exception");
            None
        }
    }
}

fn throw_exception(env: &mut EnvUnowned<'_>, class_name: &str, exception_name: &str, message: impl AsRef<str>) {
    match env
        .with_env(|env| -> jni::errors::Result<()> {
            throw_exception_env(env, class_name, exception_name, message);
            Ok(())
        })
        .into_outcome()
    {
        Outcome::Ok(()) => {}
        Outcome::Err(err) => {
            log::error!("Failed to enter JNI env while throwing {exception_name}: {err}");
        }
        Outcome::Panic(_) => {
            log::error!("Panic while preparing to throw {exception_name}");
        }
    }
}

fn throw_exception_env(env: &mut Env<'_>, class_name: &str, exception_name: &str, message: impl AsRef<str>) {
    let message = message.as_ref();
    let message_text = message.to_string();
    let class_name = JNIString::new(class_name);
    let message = JNIString::new(message);
    match env.throw_new(class_name.borrowed(), message.borrowed()) {
        Ok(()) | Err(jni::errors::Error::JavaException) => {}
        Err(err) => {
            log::error!("Failed to throw {exception_name}: {message_text}: {err}");
        }
    }
}

fn throwable_to_string(env: &mut Env<'_>, throwable: JThrowable) -> Option<String> {
    let text = env
        .call_method(throwable, jni::jni_str!("toString"), jni::jni_sig!("()Ljava/lang/String;"), &[])
        .ok()?
        .l()
        .ok()?;
    let text = unsafe { JString::from_raw(env, text.into_raw() as jstring) };
    text.try_to_string(env).ok()
}
