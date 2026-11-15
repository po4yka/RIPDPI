#![forbid(unsafe_code)]

mod dns_bootstrap;
mod dns_connect;
mod doh_resolvers;
mod dto;
mod entry;
mod entry_dispatch;
mod executor;
mod http_cancellation;
mod http_command;
mod native_ech;
mod native_ech_cancellation;
mod native_ech_endpoint;
mod native_ech_retry;
#[cfg(test)]
mod native_ech_retry_tests;
#[cfg(test)]
mod owned_stack_fingerprint_snapshot_tests;
mod redirect;
mod redirect_hop;
mod request;
mod request_builder;
mod socket_protection;
#[cfg(test)]
mod tests;
mod tls_profile;
mod url_endpoint;

use std::io;

pub fn execute(request_json: &str) -> io::Result<String> {
    http_command::execute(request_json)
}

/// A non-owning identifier for an owned HTTP request cancellation reservation.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub(crate) struct HttpRequestId(u64);

impl HttpRequestId {
    pub(crate) const fn from_jni(value: i64) -> Self {
        Self(value as u64)
    }

    pub(crate) const fn as_jni(self) -> i64 {
        self.0 as i64
    }
}

/// Reserve a request before exposing cancellation. Zero means the active limit was reached.
pub(crate) fn create_http_request() -> HttpRequestId {
    http_cancellation::create()
}

pub(crate) fn cancel_http_request(handle: HttpRequestId) {
    http_cancellation::cancel(handle);
}

/// Release only after execution returns, or when the reserved request never started.
pub(crate) fn release_http_request(handle: HttpRequestId) {
    http_cancellation::release(handle);
}

pub(crate) fn execute_http_request(handle: HttpRequestId, request_json: &str) -> io::Result<String> {
    executor::execute_cancellable(request_json, http_cancellation::subscribe(handle)?)
}

pub fn connect_ech(request_json: &str) -> io::Result<String> {
    native_ech::connect(request_json)
}

/// A non-owning identifier for a request in the ECH cancellation registry.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct EchRequestId(u64);

impl EchRequestId {
    /// Decode a Java long without changing its bit pattern.
    pub const fn from_jni(value: i64) -> Self {
        Self(value as u64)
    }

    /// Encode this identifier as the Java long used by the JNI bridge.
    pub const fn as_jni(self) -> i64 {
        self.0 as i64
    }
}

pub fn create_ech_request() -> EchRequestId {
    native_ech_cancellation::create()
}

pub fn cancel_ech_request(handle: EchRequestId) {
    native_ech_cancellation::cancel(handle);
}

pub fn release_ech_request(handle: EchRequestId) {
    native_ech_cancellation::release(handle);
}

pub fn connect_ech_request(handle: EchRequestId, request_json: &str) -> io::Result<String> {
    let receiver = native_ech_cancellation::subscribe(handle)?;
    native_ech::connect_cancellable(request_json, receiver)
}

pub use entry::{connect_ech_entry, execute_entry};
