//! Integration tests for the hs5t CLI binary.
//!
//! Run with: cargo test -p hs5t-bin --test cli_tests
//!
//! These tests spawn the compiled binary as a subprocess and assert its
//! observable behaviour (exit code, stdout, stderr, signal handling).

use std::{
    env, fs,
    process::{Command, Stdio},
    time::{Duration, Instant},
};

/// Construct a Command pointing at the hs5t binary built by Cargo.
///
/// CARGO_BIN_EXE_hs5t is set automatically by cargo when running integration
/// tests for a package that declares a [[bin]] named "hs5t".
fn hs5t() -> Command {
    Command::new(env!("CARGO_BIN_EXE_hs5t"))
}

// ── I-01: --version ──────────────────────────────────────────────────────────

/// I-01: `hs5t --version` must exit 0 and print a semver+commit string.
///
/// Expected format: "MAJOR.MINOR.MICRO COMMIT_ID" (e.g. "0.1.0 a1b2c3d").
/// The version string must contain at least one dot (semver) and a space
/// separating it from the commit hash.
#[test]
fn i01_version_flag_exits_0_with_version_string() {
    let out = hs5t()
        .arg("--version")
        .output()
        .expect("failed to spawn hs5t --version");

    assert!(
        out.status.success(),
        "--version must exit 0, got: {:?}",
        out.status
    );

    let stdout = String::from_utf8_lossy(&out.stdout);
    // Must contain a dot (semver) and a space (separating version from commit).
    assert!(
        stdout.contains('.') && stdout.contains(' '),
        "--version output must match 'MAJOR.MINOR.MICRO COMMIT_ID', got: {stdout:?}"
    );
}

// ── I-02: --help ─────────────────────────────────────────────────────────────

/// I-02: `hs5t --help` must exit 0 and print a usage message that mentions
/// the config-file argument.
#[test]
fn i02_help_flag_exits_0_with_usage() {
    let out = hs5t()
        .arg("--help")
        .output()
        .expect("failed to spawn hs5t --help");

    assert!(
        out.status.success(),
        "--help must exit 0, got: {:?}",
        out.status
    );

    let stdout = String::from_utf8_lossy(&out.stdout);
    // Usage must mention "config" (the required positional argument).
    assert!(
        stdout.to_lowercase().contains("config"),
        "--help output must mention 'config', got: {stdout:?}"
    );
}

// ── I-03: nonexistent config path ────────────────────────────────────────────

/// I-03: `hs5t /nonexistent/path` must exit with code 1 and write an error
/// message to stderr.
#[test]
fn i03_nonexistent_config_exits_1_with_stderr() {
    let out = hs5t()
        .arg("/nonexistent/path/does-not-exist.yml")
        .output()
        .expect("failed to spawn hs5t with nonexistent config");

    assert!(
        !out.status.success(),
        "nonexistent config must exit non-zero, got: {:?}",
        out.status
    );
    assert_eq!(
        out.status.code(),
        Some(1),
        "exit code must be exactly 1, got: {:?}",
        out.status
    );

    let stderr = String::from_utf8_lossy(&out.stderr);
    assert!(
        !stderr.is_empty(),
        "an error message must be written to stderr"
    );
}

// ── I-04: SIGINT → clean shutdown ────────────────────────────────────────────

/// I-04: A running hs5t process must exit cleanly (exit 0) within 3 seconds
/// after receiving SIGINT.
///
/// The test:
/// 1. Writes a minimal valid config to a temp file.
/// 2. Spawns hs5t with that config.
/// 3. Asserts the process is still alive 1 second after startup (the binary
///    must not exit immediately; it must remain running waiting for events).
/// 4. Sends SIGINT.
/// 5. Waits up to 3 s for the process to exit with status 0.
#[test]
fn i04_sigint_causes_clean_shutdown() {
    // Write a minimal valid config to a temp file.
    let tmp = env::temp_dir().join("hs5t_test_sigint_config.yml");
    fs::write(&tmp, b"socks5:\n  port: 19876\n  address: 127.0.0.1\n")
        .expect("failed to write temp config");

    let mut child = hs5t()
        .arg(tmp.to_str().expect("temp path must be valid UTF-8"))
        .env("HEV_SOCKS5_TUNNEL_FD", "0")
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::null())
        .spawn()
        .expect("failed to spawn hs5t");

    // Give the process time to reach its event loop.
    std::thread::sleep(Duration::from_secs(1));

    // The binary must still be running — it must not exit immediately on startup.
    assert!(
        child.try_wait().expect("try_wait failed").is_none(),
        "hs5t must still be running 1 second after startup (must not exit immediately)"
    );

    // Send SIGINT.
    let pid = child.id();
    // SAFETY: kill(2) with SIGINT on a valid PID of a child process we own.
    let rc = unsafe { libc::kill(pid as libc::pid_t, libc::SIGINT) };
    assert_eq!(rc, 0, "kill(SIGINT) must succeed (errno if not: {})", rc);

    // Wait up to 3 seconds for the process to exit cleanly.
    let deadline = Instant::now() + Duration::from_secs(3);
    let status = loop {
        match child.try_wait().expect("try_wait failed") {
            Some(status) => break status,
            None => {
                if Instant::now() >= deadline {
                    let _ = child.kill();
                    let _ = child.wait();
                    panic!("hs5t did not exit within 3 seconds after SIGINT");
                }
                std::thread::sleep(Duration::from_millis(50));
            }
        }
    };

    let _ = fs::remove_file(&tmp);

    assert!(
        status.success(),
        "SIGINT must produce a clean exit (exit 0), got: {:?}",
        status
    );
}
