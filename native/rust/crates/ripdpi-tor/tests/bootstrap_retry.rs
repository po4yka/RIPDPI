#![cfg(unix)]

use std::path::PathBuf;
use std::time::Duration;

use ripdpi_tor::{TorBridgePtConfig, TorError, TorPluggableTransport, TorRelayClient, build_bridge_pt_config};

/// # Cancel safety
/// NOT cancel-safe: run this regression to completion so client and runtime
/// teardown can finish after the deliberately cancelled bootstrap waiters.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn cancelled_bootstrap_retry_does_not_report_false_readiness() {
    let root = tempfile::tempdir().unwrap();
    let config = build_bridge_pt_config(TorBridgePtConfig {
        state_dir: root.path().join("state"),
        cache_dir: root.path().join("cache"),
        bridge_lines: vec!["obfs4 127.0.0.1:9 316E643333645F6D79216558614D3931657A5F5F cert=test iat-mode=0".into()],
        transports: vec![TorPluggableTransport {
            protocols: vec!["obfs4".into()],
            binary_path: PathBuf::from("/bin/sh"),
            arguments: vec![
                "-c".into(),
                "printf 'VERSION 1\\nCMETHOD obfs4 socks5 127.0.0.1:9\\nCMETHODS DONE\\n'; sleep 2".into(),
            ],
            run_on_startup: true,
        }],
    })
    .unwrap();
    let client = TorRelayClient::create_unbootstrapped(config).unwrap();
    for duration in [Duration::from_millis(500), Duration::from_millis(200)] {
        assert!(matches!(client.bootstrap_with_timeout(duration).await, Err(TorError::BootstrapTimeout { .. })));
    }
}
