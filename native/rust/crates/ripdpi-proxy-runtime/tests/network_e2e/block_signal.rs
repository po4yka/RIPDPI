use std::io::{Read, Write};
use std::net::{Ipv4Addr, TcpListener};
use std::sync::{Arc, mpsc};
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

fn request_reset_server(requests: Vec<&'static [u8]>) -> (u16, mpsc::Receiver<Vec<u8>>, std::thread::JoinHandle<()>) {
    let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).expect("bind request reset server");
    let port = listener.local_addr().expect("request reset server address").port();
    let (reset_request_tx, reset_request_rx) = mpsc::channel();
    let worker = std::thread::spawn(move || {
        for (index, expected) in requests.into_iter().enumerate() {
            let (mut stream, _) = listener.accept().expect("accept expected request");
            stream.set_read_timeout(Some(super::support::START_TIMEOUT)).expect("set request read timeout");
            let mut request = vec![0; expected.len()];
            stream.read_exact(&mut request).expect("read complete HTTP request");
            assert_eq!(request, expected);
            if index == 0 {
                socket2::SockRef::from(&stream).set_linger(Some(Duration::ZERO)).expect("abortive close");
                reset_request_tx.send(request).expect("report request before reset");
            } else {
                stream.write_all(&request).expect("echo complete request");
            }
        }
    });
    (port, reset_request_rx, worker)
}

#[test]
fn repeated_tcp_resets_confirm_blocked_host_and_expose_telemetry() {
    let _guard = super::test_guard();
    let payload_a = b"GET /one HTTP/1.1\r\nHost: blocked.example\r\n\r\n";
    let payload_b = b"GET /two HTTP/1.1\r\nHost: blocked.example\r\n\r\n";
    let payload_after = b"GET /after HTTP/1.1\r\nHost: blocked.example\r\n\r\n";
    let (port_a, reset_a, worker_a) = request_reset_server(vec![payload_a, payload_a, payload_after]);
    let (port_b, reset_b, worker_b) = request_reset_server(vec![payload_b, payload_b]);
    let telemetry = Arc::new(RecordingTelemetry::default());

    let mut config = ephemeral_proxy_config(&["-X", "--ip", "127.0.0.1"]);
    config.host_autolearn.enabled = true;
    config.host_autolearn.penalty_ttl_secs = 1;
    config.host_autolearn.max_hosts = 32;
    config.adaptive.auto_level = 0;
    let mut store_path = std::env::temp_dir();
    store_path.push(format!(
        "ripdpi-host-block-e2e-{}.json",
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).expect("system time").as_nanos()
    ));
    config.host_autolearn.store_path = Some(store_path.to_string_lossy().into_owned());
    config.timeouts.timeout_ms = 500;
    let detect = ripdpi_proxy_runtime_adapter::model::config::DETECT_TCP_RESET
        | ripdpi_proxy_runtime_adapter::model::config::DETECT_SILENT_DROP;
    let initial = DesyncGroup::new(0);
    let mut fallback_a = DesyncGroup::new(1);
    fallback_a.matches.proto = IS_TCP;
    fallback_a.matches.detect = detect;
    fallback_a.matches.port_filter = Some((port_a, port_a));
    let mut fallback_b = DesyncGroup::new(2);
    fallback_b.matches.proto = IS_TCP;
    fallback_b.matches.detect = detect;
    fallback_b.matches.port_filter = Some((port_b, port_b));
    config.groups = vec![initial, fallback_a, fallback_b];

    let proxy = start_proxy(config, Some(telemetry.clone() as Arc<dyn RuntimeTelemetrySink>));
    let body_a = socks_connect_ip_round_trip_with_retry(proxy.port, port_a, payload_a);
    assert_eq!(body_a, payload_a);
    assert_eq!(reset_a.recv_timeout(super::support::START_TIMEOUT).expect("first request before reset"), payload_a);

    std::thread::sleep(Duration::from_millis(1_100));

    let body_b = socks_connect_ip_round_trip_with_retry(proxy.port, port_b, payload_b);
    assert_eq!(body_b, payload_b);
    assert_eq!(reset_b.recv_timeout(super::support::START_TIMEOUT).expect("second request before reset"), payload_b);
    let body_after = socks_connect_ip_round_trip_with_retry(proxy.port, port_a, payload_after);
    assert_eq!(body_after, payload_after);
    worker_a.join().expect("first request reset server");
    worker_b.join().expect("second request reset server");
    drop(proxy);
    let _ = std::fs::remove_file(&store_path);

    let snapshot = telemetry.snapshot();
    let latest_state = snapshot.autolearn_states.last().expect("autolearn state update");
    assert!(latest_state.enabled);
    assert_eq!(latest_state.blocked_host_count, 1);
    assert!(matches!(latest_state.last_block_signal.as_deref(), Some("tcp_reset" | "silent_drop")));
    assert!(latest_state.penalized_host_count >= 1);
    assert_eq!(latest_state.last_block_provider, None);
    assert!(latest_state.learned_host_count >= 1);
    assert!(snapshot.autolearn_events.iter().any(|event| {
        event.action == "host_blocked"
            && event.host.as_deref() == Some("blocked.example")
            && event.group_index.is_none()
    }));
}
