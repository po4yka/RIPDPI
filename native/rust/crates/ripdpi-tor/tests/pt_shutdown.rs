#![cfg(unix)]

#[path = "../../../vendor/tor-ptmgr/src/child_process.rs"]
mod child_process;

use std::path::PathBuf;
use std::time::{Duration, Instant};

use ripdpi_tor::{TorBridgePtConfig, TorPluggableTransport, TorRelayClient, build_bridge_pt_config};

#[test]
fn quiet_managed_pt_receives_stdin_eof_after_runtime_shutdown() {
    check_shutdown(false);
    check_shutdown(true);
}

fn check_shutdown(stubborn: bool) {
    let root = tempfile::tempdir().unwrap();
    let started = root.path().join("started");
    let exited = root.path().join("exited");
    let config = build_bridge_pt_config(TorBridgePtConfig {
        state_dir: root.path().join("state"),
        cache_dir: root.path().join("cache"),
        bridge_lines: vec!["obfs4 127.0.0.1:9 316E643333645F6D79216558614D3931657A5F5F cert=test iat-mode=0".into()],
        transports: vec![TorPluggableTransport {
            protocols: vec!["obfs4".into()],
            binary_path: PathBuf::from("/bin/sh"),
            arguments: vec!["-c".into(), "printf '%s' $$ > \"$1\"; printf 'VERSION 1\\nCMETHOD obfs4 socks5 127.0.0.1:9\\nCMETHODS DONE\\n'; read ignored; printf exited > \"$2\"; if [ \"$3\" = true ]; then exec sleep 30; fi".into(), "pt".into(), started.display().to_string(), exited.display().to_string(), stubborn.to_string()],
            run_on_startup: true,
        }],
    }).unwrap();
    let runtime = tokio::runtime::Builder::new_multi_thread().worker_threads(2).enable_all().build().unwrap();
    runtime.block_on(async {
        let client = TorRelayClient::create_unbootstrapped(config).unwrap();
        let _ = client.bootstrap_with_timeout(Duration::from_millis(500)).await;
        drop(client);
    });
    runtime.shutdown_timeout(Duration::from_millis(500));
    assert!(started.exists(), "the managed PT must have started");
    let until = Instant::now() + Duration::from_secs(2);
    while !exited.exists() && Instant::now() < until {
        std::thread::sleep(Duration::from_millis(10));
    }
    let observed = exited.exists();
    let pid = std::fs::read_to_string(&started).unwrap();
    let alive = || {
        std::process::Command::new("/bin/kill")
            .args(["-0", pid.trim()])
            .stderr(std::process::Stdio::null())
            .status()
            .unwrap()
            .success()
    };
    let until = Instant::now() + Duration::from_secs(6);
    while alive() && Instant::now() < until {
        std::thread::sleep(Duration::from_millis(10));
    }
    let reaped = !alive();
    if !reaped {
        let _ = std::process::Command::new("/bin/kill").args(["-TERM", pid.trim()]).status();
    }
    assert!(observed, "quiet PT must receive EOF without further stdout");
    assert!(reaped, "the PT must exit or be killed and reaped within its grace bound");
}

/// # Cancel safety
/// cancel-safe: the missing executable creates no child or running PT tasks.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn missing_pt_binary_preserves_path_and_never_retry_classification() {
    use tor_error::{HasRetryTime, RetryTime};
    use tor_ptmgr::err::PtError;
    use tor_ptmgr::ipc::{PluggableClientTransport, PtClientParameters, PtCommonParameters};

    let _ = rustls::crypto::aws_lc_rs::default_provider().install_default();
    let root = tempfile::tempdir().unwrap();
    let missing = root.path().join("missing-transport");
    assert!(child_process::launch(std::process::Command::new(&missing), Box::new(|_| {})).is_err());
    let common = PtCommonParameters::builder().state_location(root.path().to_path_buf()).build().unwrap();
    let params = PtClientParameters::builder().transports(vec!["obfs4".parse().unwrap()]).build().unwrap();
    let mut transport = PluggableClientTransport::new(missing.clone(), vec![], common, params);
    let runtime = tor_rtcompat::PreferredRuntime::current().unwrap();
    let error = transport.launch(runtime).await.unwrap_err();
    assert!(matches!(&error, PtError::ChildSpawnFailed { path, error }
        if path == &missing && error.kind() == std::io::ErrorKind::NotFound));
    assert_eq!(error.retry_time(), RetryTime::Never);
}

#[test]
fn reader_spawn_failure_keeps_child_cleanup_owned() {
    let root = tempfile::tempdir().unwrap();
    let marker = root.path().join("pid");
    let marker_for_spawn = marker.clone();
    let mut command = std::process::Command::new("/bin/sh");
    command
        .args(["-c", "printf '%s' $$ > \"$1\"; read ignored", "pt"])
        .arg(&marker)
        .stdin(std::process::Stdio::piped())
        .stdout(std::process::Stdio::piped());
    let result = child_process::launch_with_reader_spawner(command, Box::new(|_| {}), move |_| {
        let deadline = Instant::now() + Duration::from_secs(2);
        while !marker_for_spawn.exists() && Instant::now() < deadline {
            std::thread::sleep(Duration::from_millis(10));
        }
        Err(std::io::Error::other("injected reader spawn failure"))
    });
    assert!(result.is_err());
    let pid = std::fs::read_to_string(marker).unwrap();
    let alive = || {
        std::process::Command::new("/bin/kill")
            .args(["-0", pid.trim()])
            .stderr(std::process::Stdio::null())
            .status()
            .unwrap()
            .success()
    };
    let deadline = Instant::now() + Duration::from_secs(2);
    while alive() && Instant::now() < deadline {
        std::thread::sleep(Duration::from_millis(10));
    }
    let reaped = !alive();
    if !reaped {
        let _ = std::process::Command::new("/bin/kill").args(["-TERM", pid.trim()]).status();
    }
    assert!(reaped, "failed reader startup must not orphan its child");
}
