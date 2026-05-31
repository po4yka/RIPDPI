//! Source-level audit of the `VpnService.protect()` invariant for the DNS
//! resolver's socket-creation sites.
//!
//! Per `.claude/rules/vpnservice-protect-invariant.md` § Audit, every
//! non-loopback outbound socket must be protected before `connect`/`bind`. In
//! this crate that protection is supplied by the integrator through
//! `EncryptedDnsConnectHooks` (a `direct_tcp_connector` for TCP and a
//! `direct_udp_binder` for DoQ's UDP socket). The crate intentionally retains a
//! *single* bare fallback per transport for the non-VPN / no-active-TUN case,
//! each annotated with an `// allow: hook-gated fallback` sentinel.
//!
//! This test fails the build if any **new** bare socket constructor is added to
//! production resolver code without that sentinel, so a future contributor (or
//! an LLM-authored diff) cannot silently introduce an unprotected dial. It is
//! the automated grep the rule file mandates, expressed as a test so it runs in
//! CI alongside `cargo nextest`.

use std::collections::BTreeSet;
use std::fs;
use std::path::{Path, PathBuf};

/// Bare socket constructors that bypass the protected hooks. `quinn::Endpoint::new`
/// is deliberately absent: it consumes a socket already produced by the protected
/// `direct_udp_binder`, so it is not a bare site.
const BARE_SOCKET_PATTERNS: &[&str] = &["TcpStream::connect", "UdpSocket::bind", "quinn::Endpoint::client"];

const SENTINEL: &str = "// allow: hook-gated fallback";

/// The exhaustive set of production files permitted to hold a bare fallback site,
/// expressed as paths relative to `src/`. Adding a bare site anywhere else — or
/// removing the sentinel from one of these — fails the test.
const EXPECTED_FALLBACK_FILES: &[&str] = &["resolver/tcp/tokio_connect.rs", "resolver/doq.rs"];

#[test]
fn resolver_socket_sites_are_hook_gated() {
    let resolver_root = Path::new(env!("CARGO_MANIFEST_DIR")).join("src/resolver");
    let src_root = Path::new(env!("CARGO_MANIFEST_DIR")).join("src");

    let mut rs_files = Vec::new();
    collect_rs_files(&resolver_root, &mut rs_files);
    assert!(!rs_files.is_empty(), "no .rs files found under {}", resolver_root.display());

    let mut unannotated: Vec<String> = Vec::new();
    let mut annotated_files: BTreeSet<String> = BTreeSet::new();

    for file in &rs_files {
        let contents = fs::read_to_string(file).unwrap_or_else(|err| panic!("read {}: {err}", file.display()));
        let production = strip_cfg_test_regions(&contents);
        let rel = file.strip_prefix(&src_root).unwrap_or(file).to_string_lossy().replace('\\', "/");

        for (line_no, line) in &production {
            if !BARE_SOCKET_PATTERNS.iter().any(|pattern| line.contains(pattern)) {
                continue;
            }
            if has_sentinel_above(&production, *line_no) {
                annotated_files.insert(rel.clone());
            } else {
                unannotated.push(format!("{rel}:{line_no}: {}", line.trim()));
            }
        }
    }

    assert!(
        unannotated.is_empty(),
        "found bare socket constructor(s) in production resolver code with no `{SENTINEL}` sentinel.\n\
         Every non-loopback socket must be created through a protected hook \
         (direct_tcp_connector / direct_udp_binder); see vpnservice-protect-invariant.md.\n\
         Offending sites:\n  {}",
        unannotated.join("\n  ")
    );

    let expected: BTreeSet<String> = EXPECTED_FALLBACK_FILES.iter().map(ToString::to_string).collect();
    assert_eq!(
        annotated_files, expected,
        "the set of files holding a hook-gated bare fallback changed.\n\
         If this is intentional, update EXPECTED_FALLBACK_FILES and confirm the new \
         site is genuinely a fallback reached only without a protect hook."
    );
}

/// Recursively collect `*.rs` files.
fn collect_rs_files(dir: &Path, out: &mut Vec<PathBuf>) {
    let entries = fs::read_dir(dir).unwrap_or_else(|err| panic!("read_dir {}: {err}", dir.display()));
    for entry in entries {
        let path = entry.expect("dir entry").path();
        if path.is_dir() {
            collect_rs_files(&path, out);
        } else if path.extension().is_some_and(|ext| ext == "rs") {
            out.push(path);
        }
    }
}

/// Return `(1-based line number, line)` for every line NOT inside a
/// `#[cfg(test)]`-guarded item. Test code legitimately constructs loopback
/// sockets (`turmoil`, pooled-connection fixtures) and must not be flagged.
fn strip_cfg_test_regions(contents: &str) -> Vec<(usize, &str)> {
    let lines: Vec<&str> = contents.lines().collect();
    let mut production = Vec::new();
    let mut i = 0;
    while i < lines.len() {
        if lines[i].trim_start().starts_with("#[cfg(test)]") {
            // Skip the attribute and the entire item it guards.
            i += 1;
            let mut depth: i32 = 0;
            let mut entered = false;
            while i < lines.len() {
                let line = lines[i];
                let opens = line.matches('{').count() as i32;
                let closes = line.matches('}').count() as i32;
                if opens > 0 {
                    entered = true;
                }
                depth += opens - closes;
                i += 1;
                if entered && depth <= 0 {
                    break;
                }
                // Attribute on a non-block item (e.g. `use ...;`): stop after it.
                if !entered && line.trim_end().ends_with(';') {
                    break;
                }
            }
            continue;
        }
        production.push((i + 1, lines[i]));
        i += 1;
    }
    production
}

/// True if an `// allow: hook-gated fallback` sentinel appears within the eight
/// production lines preceding `line_no` (1-based).
fn has_sentinel_above(production: &[(usize, &str)], line_no: usize) -> bool {
    production.iter().filter(|(n, _)| *n < line_no && *n + 8 >= line_no).any(|(_, line)| line.contains(SENTINEL))
}
