use std::time::{SystemTime, UNIX_EPOCH};

use ripdpi_diagnostics_tls::tls::{tls_key_log_callback_for_path, try_tls_handshake_with_key_log, TlsClientProfile};
use ripdpi_diagnostics_transport::transport::{direct_transport, TargetAddress};

#[test]
fn tls_keylog_file_receives_live_handshake_secrets_when_network_tests_enabled() {
    if std::env::var("RIPDPI_RUN_NETWORK_TESTS").as_deref() != Ok("1") {
        return;
    }

    let path = temp_keylog_path();
    let key_log = tls_key_log_callback_for_path(path.clone());
    let target = TargetAddress::Host("example.com".to_string());
    let observation = try_tls_handshake_with_key_log(
        &target,
        443,
        &direct_transport(),
        "example.com",
        true,
        TlsClientProfile::Tls13Only,
        None,
        Some(&key_log),
    );

    assert_eq!("tls_ok", observation.status, "unexpected TLS observation: {observation:?}");
    let content = std::fs::read_to_string(&path).expect("TLS keylog file should be created");
    assert!(
        content.lines().any(|line| line.starts_with("CLIENT_HANDSHAKE_TRAFFIC_SECRET ")),
        "expected TLS 1.3 client handshake secret in {path:?}, got: {content:?}",
    );
    assert!(
        content.lines().any(|line| line.starts_with("SERVER_HANDSHAKE_TRAFFIC_SECRET ")),
        "expected TLS 1.3 server handshake secret in {path:?}, got: {content:?}",
    );
}

fn temp_keylog_path() -> std::path::PathBuf {
    if let Some(path) = std::env::var_os("RIPDPI_TLS_KEYLOG_TEST_PATH") {
        return path.into();
    }
    let nanos = SystemTime::now().duration_since(UNIX_EPOCH).expect("system time").as_nanos();
    std::env::temp_dir().join(format!("ripdpi-live-keylog-{nanos}.keys"))
}
