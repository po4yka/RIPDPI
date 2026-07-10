#![forbid(unsafe_code)]

mod config;
mod errors;
mod http_body;
mod http_server;
mod path;
mod session;

use std::io;

use config::parse_config;
use errors::{classify_error, emit_structured_error};
use http_server::run;

const VERSION_FLAG: &str = "--version";

#[tokio::main(flavor = "multi_thread")]
async fn main() -> io::Result<()> {
    let mut arguments = std::env::args_os().skip(1);
    let version_only = arguments.next().is_some_and(|value| value == VERSION_FLAG) && arguments.next().is_none();
    if version_only {
        println!("ripdpi-cloudflare-origin {}", env!("CARGO_PKG_VERSION"));
        return Ok(());
    }

    match parse_config() {
        Ok(config) => run(config).await,
        Err(error) => {
            emit_structured_error(classify_error(&error), &error);
            Err(error)
        }
    }
}
