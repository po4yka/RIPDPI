use super::*;

#[test]
fn cli_limits_repeated_quic_sni_split_steps() {
    let args = vec!["--quic-sni-split".to_string(); 64];
    assert!(parse_cli(&args, &StartupEnv::default()).is_ok());
    let args = vec!["--quic-sni-split".to_string(); 65];
    assert!(parse_cli(&args, &StartupEnv::default()).is_err());
}
