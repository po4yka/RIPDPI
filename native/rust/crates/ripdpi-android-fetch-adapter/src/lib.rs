mod dns_bootstrap;
mod dto;
mod entry;
mod executor;
mod native_ech;
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
