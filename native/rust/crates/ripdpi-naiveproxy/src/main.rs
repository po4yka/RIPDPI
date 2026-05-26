#![forbid(unsafe_code)]

mod config;
mod connect_tunnel;
mod errors;
pub mod h2_connect;
pub mod padding;
mod relay;
mod socks5;
mod tls;

use std::io;

use crate::config::parse_config;
use crate::errors::emit_structured_error;
use crate::relay::run;

const VERSION_FLAG: &str = "--version";
const PROBE_FLAG: &str = "--probe";

/// Schema version of the `RIPDPI-PROBE` JSON line. Bump when adding,
/// removing, or renaming top-level fields. Service-side manager refuses
/// to start the helper if this schema is outside the range it supports.
const PROBE_SCHEMA_VERSION: u32 = 1;

/// Capability tags reported by the probe. Service code can branch on
/// these to enable features only when the helper supports them.
const PROBE_FEATURES: &[&str] = &["socks5-listener", "https-connect-upstream", "structured-error", "ready-signal"];

fn render_probe_line(schema_version: u32, helper_version: &str, features: &[&str]) -> String {
    // Hand-formatted JSON so this path stays free of a serde dependency
    // for what is meant to be a fast pre-launch check.
    let features_csv = features.iter().map(|f| format!("\"{f}\"")).collect::<Vec<_>>().join(",");
    format!(
        "RIPDPI-PROBE {{\"schema_version\":{schema_version},\"helper_version\":\"{helper_version}\",\"features\":[{features_csv}]}}"
    )
}

fn emit_probe_line() {
    println!("{}", render_probe_line(PROBE_SCHEMA_VERSION, env!("CARGO_PKG_VERSION"), PROBE_FEATURES));
}

#[cfg(test)]
mod probe_tests {
    use super::*;

    #[test]
    fn probe_line_starts_with_marker_and_is_valid_json_payload() {
        let line = render_probe_line(1, "0.1.0", &["a", "b"]);
        // Marker prefix that Android manager looks for.
        assert!(line.starts_with("RIPDPI-PROBE "), "probe line missing marker: {line}");

        let json_part = line.trim_start_matches("RIPDPI-PROBE ");
        // Fields the Android manager parses. NOTE: this `schema_version` is the
        // helper probe-line schema -- a pre-launch capability handshake
        // (snake_case key, hand-formatted, no serde). It is deliberately NOT
        // the runtime telemetry `NativeRuntimeSnapshot` schema and emits no
        // `schemaVersion` (camelCase) key. See docs/architecture/TELEMETRY_CONTRACT.md.
        assert!(json_part.contains("\"schema_version\":1"), "missing schema_version: {json_part}");
        assert!(json_part.contains("\"helper_version\":\"0.1.0\""), "missing helper_version: {json_part}");
        assert!(json_part.contains("\"features\":[\"a\",\"b\"]"), "wrong features array: {json_part}");
    }

    #[test]
    fn probe_line_carries_known_capability_tags() {
        // The capability tags the manager branches on must remain stable
        // across helper releases; bumping requires PROBE_SCHEMA_VERSION
        // to bump too.
        for required in ["socks5-listener", "https-connect-upstream", "ready-signal"] {
            assert!(PROBE_FEATURES.contains(&required), "expected feature {required} not in PROBE_FEATURES",);
        }
    }
}

#[tokio::main(flavor = "multi_thread")]
async fn main() -> io::Result<()> {
    if std::env::args().skip(1).any(|value| value == VERSION_FLAG) {
        println!("ripdpi-naiveproxy {}", env!("CARGO_PKG_VERSION"));
        return Ok(());
    }
    if std::env::args().skip(1).any(|value| value == PROBE_FLAG) {
        emit_probe_line();
        return Ok(());
    }

    match parse_config() {
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
