use std::process::Command;

#[test]
fn existing_plan_tcp_unit_tests_still_pass_by_name() {
    let output = Command::new(env!("CARGO"))
        .args(["test", "-p", "ripdpi-desync", "plan_tcp::", "--lib"])
        .current_dir(env!("CARGO_MANIFEST_DIR"))
        .output()
        .expect("run plan_tcp unit tests");

    assert!(
        output.status.success(),
        "plan_tcp unit tests failed\nstdout:\n{}\nstderr:\n{}",
        String::from_utf8_lossy(&output.stdout),
        String::from_utf8_lossy(&output.stderr),
    );
}
