use super::*;
use ripdpi_native_protect::{ProtectCallback, register_protect_callback, unregister_protect_callback};
use std::os::fd::RawFd;

#[test]
fn slow_protection_never_holds_the_probe_deadline_or_sends_after_cancel() {
    const CHILD: &str = "RIPDPI_HTTP3_SLOW_PROTECT_CHILD";
    if std::env::var_os(CHILD).is_none() {
        let status = std::process::Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "http3::socket_tests::slow_protection_never_holds_the_probe_deadline_or_sends_after_cancel",
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
    let config = Http3ProbeConfig {
        connect_ip: Some("0.0.0.0".into()),
        port: server.local_addr().unwrap().port(),
        timeout_ms: 250,
        ..Http3ProbeConfig::default()
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
            probe_http3(&config, &TransportConfig::Direct { route_experiment: None }, true, &cancel, None)
        });
        assert_eq!(result.status, if should_cancel { "CANCELLED" } else { "TIMEOUT" }, "{result:?}");
        assert_eq!(result.attempt_count, 0);
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
