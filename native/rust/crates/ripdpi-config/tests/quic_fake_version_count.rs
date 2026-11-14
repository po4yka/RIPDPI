use ripdpi_config::{ParseResult, StartupEnv, UdpChainStepKind, parse_cli};

fn args(count: usize) -> Vec<String> {
    (0..count).flat_map(|_| ["--quic-fake-version".to_string(), "0x1a2a3a4a".to_string()]).collect()
}

#[test]
fn cli_accepts_the_fake_version_chain_budget() {
    let ParseResult::Run(config) = parse_cli(&args(64), &StartupEnv::default()).expect("bounded chain") else {
        panic!("expected runnable config");
    };
    assert_eq!(config.groups[0].actions.udp_chain.len(), 64);
    assert!(config.groups[0].actions.udp_chain.iter().all(|step| step.kind == UdpChainStepKind::QuicFakeVersion));
}

#[test]
fn cli_rejects_an_excess_fake_version_step() {
    let error = parse_cli(&args(65), &StartupEnv::default()).expect_err("excess chain count");
    assert_eq!(error.option, "--quic-fake-version");
}
