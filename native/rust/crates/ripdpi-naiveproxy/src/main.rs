#![forbid(unsafe_code)]

mod config;
mod connect_tunnel;
mod errors;
mod relay;
mod socks5;
mod tls;

use std::io;

use crate::config::{parse_args, parse_config};
use crate::errors::emit_structured_error;
use crate::relay::run;

const VERSION_FLAG: &str = "--version";

#[tokio::main(flavor = "multi_thread")]
async fn main() -> io::Result<()> {
    if std::env::args().skip(1).any(|value| value == VERSION_FLAG) {
        println!("ripdpi-naiveproxy {}", env!("CARGO_PKG_VERSION"));
        return Ok(());
    }

    match parse_config(parse_args()) {
        Ok(config) => {
            if let Err(error) = run(config).await {
                emit_structured_error(&error);
                Err(error)
            } else {
                Ok(())
            }
        }
        Err(error) => {
            emit_structured_error(&error);
            Err(error)
        }
    }
}

#[cfg(test)]
mod tests;
