use super::*;
use ripdpi_native_protect::{ProtectCallback, register_protect_callback, unregister_protect_callback};
use std::os::fd::RawFd;

#[test]
fn slow_protection_never_holds_the_probe_deadline_or_sends_after_cancel() {
    const CHILD: &str = "RIPDPI_PMTU_SLOW_PROTECT_CHILD";
    if std::env::var_os(CHILD).is_none() {
        let status = std::process::Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "pmtu::socket_tests::slow_protection_never_holds_the_probe_deadline_or_sends_after_cancel",
                "--test-threads=1",
            ])
            .env(CHILD, "1")
            .status()
            .unwrap();
        assert!(status.success());
        return;
    }
    struct Slow;
    impl ProtectCallback for Slow {
        fn protect(&self, _fd: RawFd) -> std::io::Result<()> {
            std::thread::sleep(Duration::from_secs(1));
            Ok(())
        }
    }
    let server = std::net::UdpSocket::bind("127.0.0.1:0").unwrap();
    server.set_read_timeout(Some(Duration::from_millis(30))).unwrap();
    // Unspecified target invokes protection; no QUIC packet may be sent to it.
    let config = PmtuProbeConfig {
        connect_ipv4: Some("0.0.0.0".into()),
        port: server.local_addr().unwrap().port(),
        timeout_ms: 250,
        observation_ms: 250,
        ..PmtuProbeConfig::default()
    };
    register_protect_callback(Arc::new(Slow));
    for should_cancel in [false, true] {
        let cancel = AtomicBool::new(false);
        let started = Instant::now();
        let result = std::thread::scope(|scope| {
            if should_cancel {
                scope.spawn(|| {
                    std::thread::sleep(Duration::from_millis(50));
                    cancel.store(true, Ordering::Release);
                });
            }
            probe_pmtu(&config, false, &TransportConfig::Direct { route_experiment: None }, true, &cancel, None)
        });
        assert_eq!(result.status, if should_cancel { "CANCELLED" } else { "TIMEOUT" }, "{result:?}");
        assert!(!result.tls_validated);
        assert!(
            started.elapsed() < Duration::from_millis(600),
            "blocking callback held probe: {:?}",
            started.elapsed()
        );
        std::thread::sleep(Duration::from_millis(1100));
        let mut buf = [0; 2048];
        assert!(server.recv_from(&mut buf).is_err(), "abandoned protection job sent a packet");
    }
    unregister_protect_callback();
}

#[test]
fn rejected_protection_never_creates_a_quic_connection() {
    const CHILD: &str = "RIPDPI_PMTU_REJECT_PROTECT_CHILD";
    if std::env::var_os(CHILD).is_none() {
        let status = std::process::Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "pmtu::socket_tests::rejected_protection_never_creates_a_quic_connection",
                "--test-threads=1",
            ])
            .env(CHILD, "1")
            .status()
            .unwrap();
        assert!(status.success());
        return;
    }
    struct Reject;
    impl ProtectCallback for Reject {
        fn protect(&self, _fd: RawFd) -> std::io::Result<()> {
            Err(std::io::Error::new(std::io::ErrorKind::PermissionDenied, "fixture rejection"))
        }
    }
    let config = PmtuProbeConfig { connect_ipv4: Some("192.0.2.1".into()), ..Default::default() };
    register_protect_callback(Arc::new(Reject));
    let result = probe_pmtu(
        &config,
        false,
        &TransportConfig::Direct { route_experiment: None },
        true,
        &AtomicBool::new(false),
        None,
    );
    unregister_protect_callback();
    assert_eq!(result.status, "FAILED");
    assert_eq!(result.reason.as_deref(), Some("protect_failed"));
    assert!(!result.tls_validated && !result.fragmentation_prevented);
    assert_eq!(result.sent_probe_count, 0);
}
