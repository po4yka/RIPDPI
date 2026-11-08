use std::process::Command;

#[test]
fn help_lists_listener_modes() {
    let output = Command::new(env!("CARGO_BIN_EXE_ripdpi")).arg("--help").output().expect("run proxy help");
    assert!(output.status.success());
    let help = String::from_utf8(output.stdout).expect("read help");
    for option in ["--mixed", "--http-connect", "--transparent"] {
        assert!(help.split_ascii_whitespace().any(|word| word == option), "missing option {option}");
    }
}
