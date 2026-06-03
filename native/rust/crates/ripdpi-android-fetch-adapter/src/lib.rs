#![forbid(unsafe_code)]

mod dns_bootstrap;
mod dto;
mod entry;
mod entry_dispatch;
mod executor;
mod native_ech;
#[cfg(test)]
mod owned_stack_fingerprint_snapshot_tests;
mod redirect;
mod request;
mod socket_protection;
#[cfg(test)]
mod tests;
mod tls_profile;

use std::io;

pub fn execute(request_json: &str) -> io::Result<String> {
    executor::execute(request_json)
}

pub fn connect_ech(request_json: &str) -> io::Result<String> {
    native_ech::connect(request_json)
}

pub use entry::{connect_ech_entry, execute_entry};
