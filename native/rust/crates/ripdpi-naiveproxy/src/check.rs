//! `--check` mode: one CONNECT through the profile's server, one result line, exit.

use std::ffi::OsString;
use std::io;
use std::time::Duration;

use crate::config::{NaiveProxyConfig, parse_config_from_reader};
use crate::connect_tunnel::open_https_connect_tunnel;
use crate::errors::{classify_io_error, emit_structured_error};
use crate::socks5::SocksTarget;

pub(crate) const CHECK_FLAG: &str = "--check";
const CHECK_PREFIX: &str = "RIPDPI-CHECK|naiveproxy|";
/// RFC 6761 reserves `.invalid`: the server cannot resolve it, so no third host gets traffic.
pub(crate) const CHECK_TARGET: &str = "ripdpi-check.invalid";
const CHECK_TIMEOUT: Duration = Duration::from_secs(15);

/// The class of one check: `ok`, `timeout`, or a structured-error class such as
/// `auth`, `tls`, `dns` or `connect`.
///
/// `ok` means that the server answered the CONNECT without a 407, so it accepted
/// the credentials. A server with probe resistance answers a wrong password like
/// a web site, which also gives `ok`.
///
/// # Cancel safety
///
/// cancel-safe: dropping it drops the one CONNECT open, which owns its stream.
pub(crate) async fn check_outcome(config: &NaiveProxyConfig) -> &'static str {
    let target = SocksTarget::Domain(CHECK_TARGET.to_owned(), 443);
    match tokio::time::timeout(CHECK_TIMEOUT, open_https_connect_tunnel(config, &target)).await {
        Err(_) => "timeout",
        Ok(Ok(_)) => "ok",
        Ok(Err(error)) => match classify_io_error(&error) {
            "http_connect" => "ok",
            class => class,
        },
    }
}

pub(crate) fn check_line(outcome: &str) -> String {
    format!("{CHECK_PREFIX}{outcome}")
}

/// Takes the normal relay arguments and credential stdin, plus [`CHECK_FLAG`].
///
/// # Cancel safety
///
/// cancel-safe: it runs as the whole process and exits when the check ends.
pub(crate) async fn run_check_mode(args: Vec<OsString>) -> io::Result<()> {
    let args = pico_args::Arguments::from_vec(args.into_iter().filter(|value| value != CHECK_FLAG).collect());
    let config = parse_config_from_reader(args, io::stdin().lock()).inspect_err(emit_structured_error)?;
    println!("{}", check_line(check_outcome(&config).await));
    // The pooled connection task must not delay the exit.
    std::process::exit(0)
}
