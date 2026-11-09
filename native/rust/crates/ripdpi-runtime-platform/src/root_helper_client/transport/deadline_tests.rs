use std::io::Write;
use std::os::unix::net::UnixListener;
use std::time::{Duration, Instant};

use ripdpi_root_helper_protocol::{
    CAPABILITY_VERSION, CMD_PROTOCOL_PREFLIGHT, HelperResponse, PROTOCOL_VERSION, recv_message,
};

use super::ClientTransport;

#[test]
fn preflight_response_trickle_cannot_extend_the_total_deadline() {
    let path = std::env::temp_dir().join(format!(
        "rhs-trickle-{}-{}.sock",
        std::process::id(),
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).expect("clock").as_nanos()
    ));
    let listener = UnixListener::bind(&path).expect("listener");
    let sender = std::thread::spawn(move || {
        let (mut stream, _) = listener.accept().expect("client");
        recv_message(&stream, "client closed").expect("preflight request");
        let response =
            HelperResponse::success(serde_json::Value::Null).with_versions(PROTOCOL_VERSION, CAPABILITY_VERSION);
        let payload = serde_json::to_vec(&response).expect("response");
        let header = u32::try_from(payload.len()).expect("length").to_be_bytes();
        for byte in header {
            if stream.write_all(&[byte]).is_err() {
                return;
            }
            std::thread::sleep(Duration::from_millis(700));
        }
        let _ = stream.write_all(&payload);
    });
    let transport = ClientTransport::new(path.to_string_lossy().into_owned());
    let started = Instant::now();
    let result = transport.send_command_once(
        CMD_PROTOCOL_PREFLIGHT,
        serde_json::Value::Null,
        None,
        "abcdefghijklmnopqrstuvwxyzABCDEF",
    );
    let elapsed = started.elapsed();
    sender.join().expect("sender");
    std::fs::remove_file(path).expect("remove socket");

    let error = result.expect_err("a trickle response must not extend the preflight deadline");
    assert_eq!(error.kind(), std::io::ErrorKind::TimedOut);
    assert!(elapsed < Duration::from_secs(4), "waited {elapsed:?}");
}
