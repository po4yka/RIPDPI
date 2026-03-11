use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use jni::objects::JThrowable;
use jni::sys::jint;
use jni::JNIEnv;
use once_cell::sync::OnceCell;

#[cfg(target_os = "android")]
use log::LevelFilter;
use std::fmt;
#[cfg(target_os = "android")]
use tracing::Subscriber;
#[cfg(target_os = "android")]
use tracing_log::LogTracer;
#[cfg(target_os = "android")]
use tracing_subscriber::layer::{Context, Layer};
#[cfg(target_os = "android")]
use tracing_subscriber::prelude::*;
#[cfg(target_os = "android")]
use tracing_subscriber::registry::LookupSpan;

pub const JNI_VERSION: jint = jni::sys::JNI_VERSION_1_6;

pub struct HandleRegistry<T> {
    next: AtomicU64,
    inner: Mutex<HashMap<u64, Arc<T>>>,
}

impl<T> Default for HandleRegistry<T> {
    fn default() -> Self {
        Self::new()
    }
}

impl<T> HandleRegistry<T> {
    pub fn new() -> Self {
        Self { next: AtomicU64::new(1), inner: Mutex::new(HashMap::new()) }
    }

    pub fn insert(&self, value: T) -> u64 {
        let handle = self.next.fetch_add(1, Ordering::Relaxed);
        self.inner.lock().unwrap_or_else(|e| e.into_inner()).insert(handle, Arc::new(value));
        handle
    }

    pub fn get(&self, handle: u64) -> Option<Arc<T>> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner()).get(&handle).cloned()
    }

    pub fn remove(&self, handle: u64) -> Option<Arc<T>> {
        self.inner.lock().unwrap_or_else(|e| e.into_inner()).remove(&handle)
    }
}

pub fn init_android_logging(tag: &'static str) {
    static INIT: OnceCell<()> = OnceCell::new();
    INIT.get_or_init(|| {
        #[cfg(target_os = "android")]
        {
            android_logger::init_once(
                android_logger::Config::default().with_max_level(LevelFilter::Info).with_tag(tag),
            );

            let _ = LogTracer::init();

            let _ = tracing_subscriber::registry().with(AndroidLogLayer).try_init();
        }

        #[cfg(not(target_os = "android"))]
        {
            let _ = tag;
        }
    });
}

pub fn throw_illegal_argument(env: &mut JNIEnv, message: impl AsRef<str>) {
    let _ = env.throw_new("java/lang/IllegalArgumentException", message.as_ref());
}

pub fn throw_illegal_state(env: &mut JNIEnv, message: impl AsRef<str>) {
    let _ = env.throw_new("java/lang/IllegalStateException", message.as_ref());
}

pub fn throw_io_exception(env: &mut JNIEnv, message: impl AsRef<str>) {
    let _ = env.throw_new("java/io/IOException", message.as_ref());
}

pub fn throw_runtime_exception(env: &mut JNIEnv, message: impl AsRef<str>) {
    let _ = env.throw_new("java/lang/RuntimeException", message.as_ref());
}

pub fn describe_exception(env: &mut JNIEnv) -> Option<String> {
    if !env.exception_check().ok()? {
        return None;
    }
    let throwable = env.exception_occurred().ok()?;
    env.exception_clear().ok()?;
    throwable_to_string(env, throwable)
}

fn throwable_to_string(env: &mut JNIEnv, throwable: JThrowable) -> Option<String> {
    let text = env.call_method(throwable, "toString", "()Ljava/lang/String;", &[]).ok()?.l().ok()?;
    let text = jni::objects::JString::from(text);
    env.get_string(&text).ok().map(Into::into)
}

#[cfg(target_os = "android")]
struct AndroidLogLayer;

#[cfg(target_os = "android")]
impl<S> Layer<S> for AndroidLogLayer
where
    S: Subscriber + for<'span> LookupSpan<'span>,
{
    fn on_event(&self, event: &tracing::Event<'_>, _ctx: Context<'_, S>) {
        let mut visitor = MessageFieldFormatter::default();
        event.record(&mut visitor);
        let metadata = event.metadata();
        let message = visitor.finish(metadata.target());

        match *metadata.level() {
            tracing::Level::ERROR => log::error!("{message}"),
            tracing::Level::WARN => log::warn!("{message}"),
            tracing::Level::INFO => log::info!("{message}"),
            tracing::Level::DEBUG => log::debug!("{message}"),
            tracing::Level::TRACE => log::trace!("{message}"),
        }
    }
}

#[derive(Default)]
#[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
pub(crate) struct MessageFieldFormatter {
    fields: String,
}

impl MessageFieldFormatter {
    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    pub(crate) fn record_named_debug(&mut self, field: &str, value: &dyn fmt::Debug) {
        self.push_value(field, &format!("{value:?}"));
    }

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    pub(crate) fn record_named_str(&mut self, field: &str, value: &str) {
        self.push_value(field, value);
    }

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    pub(crate) fn finish(self, target: &str) -> String {
        if self.fields.is_empty() {
            target.to_string()
        } else {
            self.fields
        }
    }

    #[cfg_attr(not(any(test, target_os = "android")), allow(dead_code))]
    fn push_value(&mut self, field: &str, value: &str) {
        if !self.fields.is_empty() {
            self.fields.push(' ');
        }
        if field == "message" {
            self.fields.push_str(value);
        } else {
            self.fields.push_str(field);
            self.fields.push('=');
            self.fields.push_str(value);
        }
    }
}

impl tracing::field::Visit for MessageFieldFormatter {
    fn record_debug(&mut self, field: &tracing::field::Field, value: &dyn fmt::Debug) {
        self.record_named_debug(field.name(), value);
    }

    fn record_str(&mut self, field: &tracing::field::Field, value: &str) {
        self.record_named_str(field.name(), value);
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use golden_test_support::assert_text_golden;

    #[test]
    fn formatter_renders_plain_message_golden() {
        let mut formatter = MessageFieldFormatter::default();
        formatter.record_named_str("message", "proxy started");

        assert_text_golden(
            env!("CARGO_MANIFEST_DIR"),
            "tests/golden/plain_message.txt",
            &formatter.finish("fallback.target"),
        );
    }

    #[test]
    fn formatter_renders_structured_fields_golden() {
        let mut formatter = MessageFieldFormatter::default();
        formatter.record_named_str("message", "route selected");
        formatter.record_named_str("target", "203.0.113.10:443");
        formatter.record_named_debug("group", &2);

        assert_text_golden(
            env!("CARGO_MANIFEST_DIR"),
            "tests/golden/structured_fields.txt",
            &formatter.finish("fallback.target"),
        );
    }

    #[test]
    fn formatter_falls_back_to_target_when_message_is_absent() {
        let formatter = MessageFieldFormatter::default();
        assert_text_golden(
            env!("CARGO_MANIFEST_DIR"),
            "tests/golden/fallback_target.txt",
            &formatter.finish("ripdpi.native"),
        );
    }

    #[test]
    fn formatter_preserves_debug_quotes_for_non_message_fields() {
        let mut formatter = MessageFieldFormatter::default();
        formatter.record_named_str("message", "tunnel error");
        formatter.record_named_debug("error", &"unexpected eof");

        assert_text_golden(
            env!("CARGO_MANIFEST_DIR"),
            "tests/golden/debug_quotes.txt",
            &formatter.finish("fallback.target"),
        );
    }
}
