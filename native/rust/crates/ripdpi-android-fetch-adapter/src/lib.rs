#![forbid(unsafe_code)]

mod dns_bootstrap;
mod dns_connect;
mod doh_resolvers;
mod dto;
mod entry;
mod entry_dispatch;
mod executor;
mod native_ech;
mod native_ech_cancellation;
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
    executor::execute(request_json)
}

pub fn connect_ech(request_json: &str) -> io::Result<String> {
    native_ech::connect(request_json)
}

pub fn create_ech_request() -> u64 {
    native_ech_cancellation::create()
}

pub fn cancel_ech_request(handle: u64) {
    native_ech_cancellation::cancel(handle);
}

pub fn release_ech_request(handle: u64) {
    native_ech_cancellation::release(handle);
}

pub fn connect_ech_request(handle: u64, request_json: &str) -> io::Result<String> {
    let receiver = native_ech_cancellation::subscribe(handle)?;
    native_ech::connect_cancellable(request_json, receiver)
}

pub use entry::{connect_ech_entry, execute_entry};
