use std::process::Command;

fn rust_bin() -> &'static str {
    env!("CARGO_BIN_EXE_ciadpi")
}

fn run(args: &[&str], dry_run: bool) -> std::process::Output {
    let mut command = Command::new(rust_bin());
    command.args(args);
    if dry_run {
        command.env("CIADPI_RS_DRY_RUN", "1");
    }
    command.output().expect("run ciadpi")
}

#[test]
fn help_and_version_are_available() {
    let help = run(&["--help"], false);
    assert!(help.status.success());
    let help_text = String::from_utf8(help.stdout).expect("utf8 help");
    assert!(help_text.contains("--no-domain"));
    assert!(help_text.contains("--cache-file"));
    assert!(help_text.contains("--split"));
    assert!(help_text.contains("--fake-offset"));

    let version = run(&["--version"], false);
    assert!(version.status.success());
    assert_eq!(
        String::from_utf8(version.stdout)
            .expect("utf8 version")
            .trim(),
        "17.3"
    );
}

#[test]
fn invalid_value_fails_with_contract_error_shape() {
    let output = run(&["--ttl", "999"], false);
    assert!(!output.status.success());
    let stderr = String::from_utf8(output.stderr).expect("utf8 stderr");
    assert!(stderr.contains("invalid value: -t 999"));
}

#[test]
fn invalid_argument_fails_with_diagnostic_output() {
    let output = run(&["--definitely-invalid"], false);
    assert!(!output.status.success());
    assert!(!output.stderr.is_empty() || !output.stdout.is_empty());
}

#[test]
fn dry_run_accepts_valid_runtime_config() {
    let output = run(
        &[
            "--ip",
            "127.0.0.1",
            "--port",
            "2080",
            "--cache-file",
            "-",
            "--auto",
            "torst",
            "--split",
            "1+s",
            "--mod-http",
            "r,h",
        ],
        true,
    );
    assert!(output.status.success());
}

#[test]
fn dry_run_accepts_shadowsocks_bootstrap_env() {
    let output = Command::new(rust_bin())
        .env("CIADPI_RS_DRY_RUN", "1")
        .env("SS_LOCAL_PORT", "1443")
        .env(
            "SS_PLUGIN_OPTIONS",
            "--no-domain --no-udp --auto torst --split 1+s --to-socks5 127.0.0.1:1081",
        )
        .output()
        .expect("run ciadpi");
    assert!(output.status.success());
}
