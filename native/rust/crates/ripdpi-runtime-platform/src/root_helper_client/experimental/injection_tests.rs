use std::net::{Ipv4Addr, SocketAddr};
use std::os::unix::net::UnixListener;
use std::path::PathBuf;
use std::time::{Duration, Instant};

use ripdpi_root_helper_protocol::{
    CAPABILITY_VERSION, CMD_SEND_SYN_HIDE_TCP, HelperResponse, PROTOCOL_VERSION, recv_message, send_message,
};

use super::super::{RootHelperClient, is_injection_paused};

const TARGET: SocketAddr = SocketAddr::new(std::net::IpAddr::V4(Ipv4Addr::new(203, 0, 113, 10)), 443);
const PACKET: [u8; 4] = [0x45, 0x00, 0x00, 0x14];

struct HelperSocket {
    socket_path: PathBuf,
    nonce_path: String,
    listener: UnixListener,
}

impl HelperSocket {
    fn bind(name: &str) -> Self {
        let socket_path = std::env::temp_dir().join(format!(
            "rh-{name}-{}-{}.sock",
            std::process::id(),
            std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).expect("clock").as_nanos()
        ));
        let nonce_path = format!("{}.nonce", socket_path.to_string_lossy());
        std::fs::write(&nonce_path, "abcdefghijklmnopqrstuvwxyzABCDEF").expect("write nonce");
        let listener = UnixListener::bind(&socket_path).expect("bind helper socket");
        Self { socket_path, nonce_path, listener }
    }

    fn client(&self, cooldown: Duration) -> RootHelperClient {
        RootHelperClient::with_injection_cooldown(self.socket_path.to_string_lossy().into_owned(), cooldown)
    }

    /// Accepts and closes every pending connection; returns how many there were.
    fn drain_connections(&self) -> usize {
        self.listener.set_nonblocking(true).expect("nonblocking listener");
        let mut count = 0;
        while self.listener.accept().is_ok() {
            count += 1;
        }
        count
    }
}

impl Drop for HelperSocket {
    fn drop(&mut self) {
        let _ = std::fs::remove_file(&self.socket_path);
        let _ = std::fs::remove_file(&self.nonce_path);
    }
}

#[test]
fn silent_helper_pauses_injection_until_the_cooldown_ends() {
    // The listener never accepts, so each connect lands in the backlog and is never answered.
    let helper = HelperSocket::bind("silent");
    let client = helper.client(Duration::from_millis(300));

    let started = Instant::now();
    let error = client.send_raw_ip_packet(TARGET, &PACKET).expect_err("silent helper");
    assert!(started.elapsed() < Duration::from_secs(2), "waited {:?}", started.elapsed());
    assert!(!is_injection_paused(&error));
    assert_eq!(helper.drain_connections(), 1, "the unanswered preflight ends the call");

    let error = client.send_raw_ip_packet(TARGET, &PACKET).expect_err("paused");
    assert!(is_injection_paused(&error));
    assert_eq!(helper.drain_connections(), 0, "a paused injection must not connect");

    std::thread::sleep(Duration::from_millis(400));
    let error = client.send_raw_ip_packet(TARGET, &PACKET).expect_err("still silent");
    assert!(!is_injection_paused(&error));
    assert_eq!(helper.drain_connections(), 1, "one call retries after the cooldown");
}

#[test]
fn slow_helper_fails_injection_but_not_other_commands() {
    let helper = HelperSocket::bind("slow");
    let listener = helper.listener.try_clone().expect("clone listener");
    // Raw preflight, then the SYN-hide preflight and command; each answer comes after the injection bound.
    let server = std::thread::spawn(move || {
        for _ in 0..3 {
            let (stream, _) = listener.accept().expect("accept");
            recv_message(&stream, "client closed").expect("request");
            std::thread::sleep(Duration::from_millis(400));
            let response = serde_json::to_vec(
                &HelperResponse::success(serde_json::Value::Null).with_versions(PROTOCOL_VERSION, CAPABILITY_VERSION),
            )
            .expect("response JSON");
            let _ = send_message(&stream, &response, None);
        }
    });
    let client = helper.client(Duration::from_secs(30));

    let error = client.send_raw_ip_packet(TARGET, &PACKET).expect_err("slow helper exceeds the injection bound");
    assert!(!is_injection_paused(&error));
    client
        .transport
        .send_command(CMD_SEND_SYN_HIDE_TCP, serde_json::json!({ "ttl": 1 }), None)
        .expect("other commands keep the long timeouts");

    server.join().expect("helper server");
}
