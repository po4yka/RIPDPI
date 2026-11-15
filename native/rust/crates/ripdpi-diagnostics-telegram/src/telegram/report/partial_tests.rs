use std::net::{Ipv4Addr, TcpListener};
use std::sync::atomic::{AtomicBool, Ordering};

use super::*;

#[test]
fn checkpoint_keeps_the_completed_download_when_following_legs_are_cancelled() {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind TLS peer");
    let media_url = format!("https://{}/", listener.local_addr().expect("TLS peer address"));
    let peer = std::thread::spawn(move || listener.accept().map(|_| ()));
    let target = TelegramTarget {
        media_url,
        upload_ip: "unused.invalid".into(),
        upload_port: 443,
        dc_endpoints: vec![],
        stall_timeout_ms: 500,
        total_timeout_ms: 1000,
        upload_size_bytes: 10,
    };
    let cancel = AtomicBool::new(false);
    let should_abort = || cancel.load(Ordering::Relaxed).then_some("cancelled");
    let mut checkpoints = Vec::new();
    let result = run_telegram_probe_with_abort_and_checkpoint(
        &target,
        &crate::transport::direct_transport(),
        None,
        &should_abort,
        &mut |checkpoint| {
            checkpoints.push(checkpoint);
            cancel.store(true, Ordering::Relaxed);
        },
    );
    peer.join().expect("join TLS peer").expect("accept TCP connection");
    let detail = |probe: &ProbeResult, key: &str| probe.details.iter().find(|d| d.key == key).map(|d| d.value.clone());
    assert_eq!(checkpoints.len(), 1);
    assert_eq!(checkpoints[0].outcome, "unknown");
    assert_eq!(detail(&checkpoints[0], "downloadStatus").as_deref(), Some("error"));
    assert_eq!(detail(&checkpoints[0], "uploadStatus").as_deref(), Some("not_run"));
    assert_eq!(detail(&checkpoints[0], "wsTunnelStatus").as_deref(), Some("not_run"));
    assert_eq!(result.outcome, "cancelled");
    assert_eq!(detail(&result, "downloadStatus"), detail(&checkpoints[0], "downloadStatus"));
    assert_eq!(detail(&result, "uploadStatus").as_deref(), Some("not_run"));
}
