use std::io::{Read, Write};
use std::net::{Ipv4Addr, TcpListener};
use std::sync::Arc;
use std::time::Duration;

use ripdpi_packets::IS_TCP;
use ripdpi_proxy_runtime_adapter::model::config::DesyncGroup;
use ripdpi_proxy_runtime_adapter::model::runtime_api::RuntimeTelemetrySink;

use super::RecordingTelemetry;
use super::support::proxy::{ephemeral_proxy_config, start_proxy};
use super::support::socks5::socks_connect_ip_round_trip_with_retry;

#[test]
fn single_tcp_reset_does_not_confirm_blocked_host() {
    let _guard = super::test_guard();
    let telemetry = Arc::new(RecordingTelemetry::default());
    // The first connection is aborted with a real RST after the request; later ones echo.
    let server = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind reset server");
    let port = server.local_addr().expect("reset server address").port();
    std::thread::spawn(move || {
        for (index, stream) in server.incoming().enumerate() {
            let Ok(mut stream) = stream else { return };
            std::thread::spawn(move || {
                let mut buf = [0u8; 4096];
                let Ok(read) = stream.read(&mut buf) else { return };
                if index == 0 {
                    socket2::SockRef::from(&stream).set_linger(Some(Duration::ZERO)).expect("abortive close");
                    return;
                }
                let _ = stream.write_all(&buf[..read]);
            });
        }
    });

    let mut config = ephemeral_proxy_config(&["-X", "--ip", "127.0.0.1"]);
    config.host_autolearn.enabled = true;
    config.host_autolearn.max_hosts = 32;
    config.adaptive.auto_level = 0;
    let mut store_path = std::env::temp_dir();
    store_path.push(format!(
        "ripdpi-single-reset-e2e-{}.json",
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).expect("system time").as_nanos()
    ));
    config.host_autolearn.store_path = Some(store_path.to_string_lossy().into_owned());
    config.groups[0].matches.detect = ripdpi_proxy_runtime_adapter::model::config::DETECT_TCP_RESET;
    config.timeouts.timeout_ms = 500;
    let mut fallback = DesyncGroup::new(1);
    fallback.matches.proto = IS_TCP;
    fallback.matches.port_filter = Some((port, port));
    config.groups.push(fallback);

    let proxy = start_proxy(config, Some(telemetry.clone() as Arc<dyn RuntimeTelemetrySink>));
    let payload = b"GET /one HTTP/1.1\r\nHost: single-reset.example\r\n\r\n".to_vec();
    let body = socks_connect_ip_round_trip_with_retry(proxy.port, port, &payload);
    assert_eq!(body, payload);
    drop(proxy);
    let _ = std::fs::remove_file(&store_path);

    let snapshot = telemetry.snapshot();
    let latest_state = snapshot.autolearn_states.last().expect("autolearn state update");
    assert_eq!(latest_state.last_block_signal, None, "a single reset is pending, never confirmed");
    assert_eq!(latest_state.blocked_host_count, 0, "one reset is a single signal and must not confirm a block");
    assert!(!snapshot.autolearn_events.iter().any(|event| event.action == "host_blocked"));
}
