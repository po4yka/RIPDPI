use std::io;
use std::net::{TcpListener, TcpStream};
use std::os::fd::AsFd;
use std::os::unix::net::UnixListener;

use ripdpi_privileged_ops::IpFragmentSendError;
use ripdpi_root_helper_protocol::{
    CAPABILITY_VERSION, CMD_SEND_IP_FRAGMENTED_TCP, HelperResponse, PROTOCOL_VERSION, recv_message, send_message,
};

use super::super::ClientTransport;

fn failure(data: serde_json::Value) -> io::Error {
    let unique = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).expect("clock").as_nanos();
    let path = std::env::temp_dir().join(format!("rhs-e-{}-{unique}.sock", std::process::id()));
    let listener = UnixListener::bind(&path).expect("helper listener");
    let worker = std::thread::spawn(move || {
        let (stream, _) = listener.accept().expect("helper client");
        let (_, fd) = recv_message(&stream, "client closed").expect("fragment request");
        let _passed = TcpStream::from(fd.expect("TCP descriptor"));
        let mut response = HelperResponse::error("fragment flow rejected");
        response.data = data;
        let response = response.with_versions(PROTOCOL_VERSION, CAPABILITY_VERSION);
        send_message(&stream, &serde_json::to_vec(&response).expect("error response"), None).expect("reply");
    });
    let listener = TcpListener::bind("127.0.0.1:0").expect("TCP listener");
    let stream = TcpStream::connect(listener.local_addr().expect("TCP address")).expect("TCP stream");
    let (_peer, _) = listener.accept().expect("TCP peer");
    let result = ClientTransport::new(path.to_string_lossy().into_owned()).send_command_once(
        CMD_SEND_IP_FRAGMENTED_TCP,
        serde_json::json!({"payload":[1,2,3],"split_offset":1,"default_ttl":64}),
        Some(stream.as_fd()),
        "abcdefghijklmnopqrstuvwxyzABCDEF",
        None,
    );
    worker.join().expect("helper fixture");
    std::fs::remove_file(path).expect("remove socket");
    result.expect_err("fragment send failed")
}

#[test]
fn fragment_helper_error_preserves_busy_kind_and_emission_boundary() {
    for uncertain in [false, true] {
        let error = failure(serde_json::json!({
            "error_kind":"WouldBlock", "raw_os_error":null, "payload_commit_uncertain":uncertain,
        }));
        assert_eq!(error.kind(), io::ErrorKind::WouldBlock);
        let phase = error.get_ref().and_then(|inner| inner.downcast_ref::<IpFragmentSendError>()).expect("phase");
        assert_eq!(phase.payload_commit_uncertain, uncertain);
    }
}

#[test]
fn fragment_helper_error_preserves_normalized_kind_and_original_errno() {
    let error = failure(serde_json::json!({
        "error_kind":"Unsupported", "raw_os_error":libc::EPERM, "payload_commit_uncertain":false,
    }));
    assert_eq!(error.kind(), io::ErrorKind::Unsupported);
    let phase = error.get_ref().and_then(|inner| inner.downcast_ref::<IpFragmentSendError>()).expect("phase");
    assert!(!phase.payload_commit_uncertain);
    assert_eq!(phase.raw_os_error(), Some(libc::EPERM));
}

#[test]
fn legacy_or_invalid_fragment_error_cannot_claim_a_pre_send_boundary() {
    let error = failure(serde_json::Value::Null);
    assert_eq!(error.kind(), io::ErrorKind::Other);
    assert!(error.get_ref().and_then(|inner| inner.downcast_ref::<IpFragmentSendError>()).is_none());
    for data in [
        serde_json::json!({"error_kind":"WouldBlock"}),
        serde_json::json!({"error_kind":"Invented","raw_os_error":null,"payload_commit_uncertain":false}),
        serde_json::json!({"error_kind":"WouldBlock","raw_os_error":-1,"payload_commit_uncertain":false}),
        serde_json::json!({"error_kind":"WouldBlock","raw_os_error":null,"payload_commit_uncertain":"false"}),
    ] {
        let error = failure(data);
        assert_eq!(error.kind(), io::ErrorKind::InvalidData);
        assert!(error.get_ref().and_then(|inner| inner.downcast_ref::<IpFragmentSendError>()).is_none());
    }
}
