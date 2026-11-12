use std::io;
use std::net::{TcpListener, TcpStream};
use std::os::fd::AsFd;
use std::os::unix::net::UnixListener;

use ripdpi_privileged_ops::TcpSendProgressError;
use ripdpi_root_helper_protocol::{CAPABILITY_VERSION, HelperResponse, PROTOCOL_VERSION, recv_message, send_message};

use super::{CMD_SEND_FAKE_TCP, ClientTransport};

fn helper_failure(data: serde_json::Value) -> io::Error {
    let unique = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).expect("clock").as_nanos();
    let path = std::env::temp_dir().join(format!("rhs-p-{}-{unique}.sock", std::process::id()));
    let listener = UnixListener::bind(&path).expect("helper listener");
    let server = std::thread::spawn(move || {
        let (stream, _) = listener.accept().expect("helper client");
        let (_request, _fd) = recv_message(&stream, "client closed").expect("request");
        let mut response = HelperResponse::error("queue did not drain");
        response.data = data;
        let response = response.with_versions(PROTOCOL_VERSION, CAPABILITY_VERSION);
        send_message(&stream, &serde_json::to_vec(&response).expect("response"), None).expect("send response");
    });
    let tcp_listener = TcpListener::bind("127.0.0.1:0").expect("TCP listener");
    let stream = TcpStream::connect(tcp_listener.local_addr().expect("TCP address")).expect("TCP stream");
    let (_peer, _) = tcp_listener.accept().expect("TCP peer");
    let result = ClientTransport::new(path.to_string_lossy().into_owned()).send_command_once(
        CMD_SEND_FAKE_TCP,
        serde_json::json!({ "original_prefix": [1, 2, 3], "fake_prefix": [0, 0, 0], "ttl": 1, "default_ttl": 64 }),
        Some(stream.as_fd()),
        "abcdefghijklmnopqrstuvwxyzABCDEF",
        None,
    );
    server.join().expect("helper server");
    std::fs::remove_file(path).expect("remove helper socket");
    result.expect_err("helper send failed")
}

#[test]
fn helper_error_keeps_bounded_committed_progress() {
    for count in [1, 3] {
        let error = helper_failure(serde_json::json!({ "bytes_committed": count }));
        let progress =
            error.get_ref().and_then(|source| source.downcast_ref::<TcpSendProgressError>()).expect("progress");
        assert_eq!(progress.bytes_committed, count);
        assert_eq!(progress.source.to_string(), "queue did not drain");
    }
}

#[test]
fn helper_error_rejects_invalid_committed_progress() {
    for value in [serde_json::json!(-1), serde_json::json!(4), serde_json::json!("3"), serde_json::Value::Null] {
        assert_eq!(helper_failure(serde_json::json!({ "bytes_committed": value })).kind(), io::ErrorKind::InvalidData);
    }
}

#[test]
fn helper_error_without_progress_keeps_legacy_error() {
    let error = helper_failure(serde_json::Value::Null);
    assert_eq!(error.kind(), io::ErrorKind::Other);
    assert_eq!(error.to_string(), "queue did not drain");
}
