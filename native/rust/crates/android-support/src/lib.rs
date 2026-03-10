use std::collections::HashMap;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, Mutex};

use jni::objects::JThrowable;
use jni::sys::jint;
use jni::JNIEnv;
use once_cell::sync::OnceCell;

#[cfg(target_os = "android")]
use log::LevelFilter;
#[cfg(target_os = "android")]
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
        Self {
            next: AtomicU64::new(1),
            inner: Mutex::new(HashMap::new()),
        }
    }

    pub fn insert(&self, value: T) -> u64 {
        let handle = self.next.fetch_add(1, Ordering::Relaxed);
        self.inner
            .lock()
            .expect("handle registry poisoned")
            .insert(handle, Arc::new(value));
        handle
    }

    pub fn get(&self, handle: u64) -> Option<Arc<T>> {
        self.inner
            .lock()
            .expect("handle registry poisoned")
            .get(&handle)
            .cloned()
    }

    pub fn remove(&self, handle: u64) -> Option<Arc<T>> {
        self.inner
            .lock()
            .expect("handle registry poisoned")
            .remove(&handle)
    }
}

pub fn init_android_logging(tag: &'static str) {
    static INIT: OnceCell<()> = OnceCell::new();
    INIT.get_or_init(|| {
        #[cfg(target_os = "android")]
        {
            android_logger::init_once(
                android_logger::Config::default()
                    .with_max_level(LevelFilter::Info)
                    .with_tag(tag),
            );

            let _ = LogTracer::init();

            let _ = tracing_subscriber::registry()
                .with(AndroidLogLayer)
                .try_init();
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
    let text = env
        .call_method(throwable, "toString", "()Ljava/lang/String;", &[])
        .ok()?
        .l()
        .ok()?;
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
        let mut visitor = MessageVisitor::default();
        event.record(&mut visitor);
        let metadata = event.metadata();
        let message = if visitor.fields.is_empty() {
            metadata.target().to_string()
        } else {
            visitor.fields
        };

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
#[cfg(target_os = "android")]
struct MessageVisitor {
    fields: String,
}

#[cfg(target_os = "android")]
impl tracing::field::Visit for MessageVisitor {
    fn record_debug(&mut self, field: &tracing::field::Field, value: &dyn fmt::Debug) {
        if !self.fields.is_empty() {
            self.fields.push(' ');
        }
        if field.name() == "message" {
            self.fields.push_str(&format!("{value:?}"));
        } else {
            self.fields.push_str(field.name());
            self.fields.push('=');
            self.fields.push_str(&format!("{value:?}"));
        }
    }

    fn record_str(&mut self, field: &tracing::field::Field, value: &str) {
        if !self.fields.is_empty() {
            self.fields.push(' ');
        }
        if field.name() == "message" {
            self.fields.push_str(value);
        } else {
            self.fields.push_str(field.name());
            self.fields.push('=');
            self.fields.push_str(value);
        }
    }
}
