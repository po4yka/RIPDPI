use std::process::Command;

const ISOLATED_CASE_ENV: &str = "RIPDPI_ISOLATED_IO_LOOP_TEST";

/// Run process-global epoch mutations apart from parallel unit tests.
pub(in crate::io_loop) fn run_isolated_test(module_path: &str, case: &str, body: impl FnOnce()) {
    let module = module_path.split_once("::").map_or(module_path, |(_, module)| module);
    let test_name = format!("{module}::{case}");
    let completed = format!("RIPDPI_ISOLATED_COMPLETED:{test_name}");
    if std::env::var(ISOLATED_CASE_ENV).ok().as_deref() == Some(test_name.as_str()) {
        body();
        println!("{completed}");
        return;
    }

    let output = Command::new(std::env::current_exe().expect("unit-test executable"))
        .args(["--exact", &test_name, "--nocapture", "--test-threads=1"])
        .env(ISOLATED_CASE_ENV, &test_name)
        .output()
        .expect("isolated unit-test process");
    let stdout = String::from_utf8_lossy(&output.stdout);
    let stderr = String::from_utf8_lossy(&output.stderr);
    assert!(output.status.success(), "isolated test failed: {stdout}\n{stderr}");
    assert!(stdout.contains(completed.as_str()), "isolated body did not complete: {stdout}\n{stderr}");
}
