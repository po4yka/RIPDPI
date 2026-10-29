//! Embeds the shared-priors release public key.
//!
//! The key comes from `RIPDPI_SHARED_PRIORS_PUB_KEY` (64 hex characters). It
//! is written to `OUT_DIR` and `include!`d by `manifest.rs`, so compiler
//! wrappers such as sccache hash the key as source content. Unset or empty
//! keeps the all-zero "not configured" key; a malformed value fails the build.

#[path = "src/key_hex.rs"]
mod key_hex;

use std::env;
use std::fmt::Write as _;
use std::fs;
use std::path::PathBuf;
use std::process::ExitCode;

const ENV_NAME: &str = "RIPDPI_SHARED_PRIORS_PUB_KEY";

fn main() -> ExitCode {
    println!("cargo:rerun-if-env-changed={ENV_NAME}");
    println!("cargo:rerun-if-changed=build.rs");
    println!("cargo:rerun-if-changed=src/key_hex.rs");

    let raw = env::var(ENV_NAME).ok();
    let key = match key_hex::resolve_pub_key(raw.as_deref()) {
        Ok(key) => key,
        Err(err) => {
            eprintln!("error: {ENV_NAME} {err}");
            return ExitCode::FAILURE;
        }
    };
    let mut literal = String::from("[");
    for byte in key {
        // Writing to a String cannot fail.
        let _ = write!(literal, "0x{byte:02x}, ");
    }
    literal.push(']');

    let Some(out_dir) = env::var_os("OUT_DIR") else {
        eprintln!("error: OUT_DIR is not set");
        return ExitCode::FAILURE;
    };
    let path = PathBuf::from(out_dir).join("shared_priors_pub_key.rs");
    if let Err(err) = fs::write(&path, literal) {
        eprintln!("error: cannot write {}: {err}", path.display());
        return ExitCode::FAILURE;
    }
    ExitCode::SUCCESS
}
