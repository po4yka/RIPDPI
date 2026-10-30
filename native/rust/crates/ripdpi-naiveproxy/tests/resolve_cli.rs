//! Process-level contract of `--resolve`. No test here touches the network.

use std::process::{Command, Output};

fn run(args: &[&str]) -> Output {
    Command::new(env!("CARGO_BIN_EXE_ripdpi-naiveproxy")).args(args).env_remove("RIPDPI_PROTECT_PATH").output().unwrap()
}

#[test]
fn ip_literals_print_one_resolved_line_and_exit_zero() {
    for (host, expected) in [("1.2.3.4", "RIPDPI-RESOLVED|1.2.3.4\n"), ("::1", "RIPDPI-RESOLVED|::1\n")] {
        let out = run(&["--resolve", host, "--server-port", "443"]);
        assert!(out.status.success(), "{host}");
        assert_eq!(String::from_utf8_lossy(&out.stdout), expected);
    }
}

#[test]
fn invalid_arguments_print_a_dns_error_line_and_exit_nonzero() {
    for args in [
        &["--resolve", "a.example"][..],
        &["--resolve", "a.example", "--server-port", "443", "--listen", "127.0.0.1:1"],
    ] {
        let out = run(args);
        assert!(!out.status.success(), "{args:?}");
        assert!(out.stdout.is_empty(), "{args:?}");
        let stderr = String::from_utf8_lossy(&out.stderr);
        assert!(stderr.starts_with("RIPDPI-ERROR|naiveproxy|dns|"), "{stderr}");
        assert_eq!(stderr.lines().count(), 1, "{stderr}");
    }
}

#[cfg(unix)]
#[test]
fn non_utf8_arguments_print_a_dns_error_line_without_panicking() {
    use std::ffi::OsStr;
    use std::os::unix::ffi::OsStrExt;

    let bad = OsStr::from_bytes(b"a\xffb.example");
    let out = Command::new(env!("CARGO_BIN_EXE_ripdpi-naiveproxy"))
        .arg("--resolve")
        .arg(bad)
        .args(["--server-port", "443"])
        .env_remove("RIPDPI_PROTECT_PATH")
        .output()
        .unwrap();
    assert_eq!(out.status.code(), Some(1));
    let stderr = String::from_utf8_lossy(&out.stderr);
    assert!(stderr.starts_with("RIPDPI-ERROR|naiveproxy|dns|"), "{stderr}");
    assert!(!stderr.contains("panicked"), "{stderr}");
}

#[test]
fn resolve_is_detected_only_as_the_first_argument() {
    let out = run(&["--server-port", "443", "--resolve", "1.2.3.4"]);
    assert!(!String::from_utf8_lossy(&out.stdout).starts_with("RIPDPI-RESOLVED|"));
}
