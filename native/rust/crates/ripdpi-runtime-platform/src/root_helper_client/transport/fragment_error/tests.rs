use std::io;
use std::net::{TcpListener, TcpStream, UdpSocket};
use std::os::fd::{AsFd, OwnedFd};
use std::os::unix::net::UnixListener;

use ripdpi_privileged_ops::IpFragmentSendError;
use ripdpi_root_helper_protocol::{
    CAPABILITY_VERSION, CMD_SEND_FAKE_RST, CMD_SEND_IP_FRAGMENTED_TCP, CMD_SEND_IP_FRAGMENTED_UDP, HelperResponse, PROTOCOL_VERSION,
    recv_message, send_message,
};

use super::super::ClientTransport;

fn failure_for(command: &'static str, data: serde_json::Value) -> io::Error {
    let unique = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).expect("clock").as_nanos();
    let path = std::env::temp_dir().join(format!("rhs-e-{}-{unique}.sock", std::process::id()));
    let listener = UnixListener::bind(&path).expect("helper listener");
    let worker = std::thread::spawn(move || {
        let (stream, _) = listener.accept().expect("helper client");
        let (_, fd) = recv_message(&stream, "client closed").expect("fragment request");
        let fd = fd.expect("transport descriptor");
        if command == CMD_SEND_IP_FRAGMENTED_UDP {
            UdpSocket::from(fd).peer_addr().expect("connected UDP descriptor");
        } else {
            TcpStream::from(fd).peer_addr().expect("connected TCP descriptor");
        }
        let mut response = HelperResponse::error("fragment flow rejected");
        response.data = data;
        let response = response.with_versions(PROTOCOL_VERSION, CAPABILITY_VERSION);
        send_message(&stream, &serde_json::to_vec(&response).expect("error response"), None).expect("reply");
    });
    let (stream, _peer): (OwnedFd, Option<TcpStream>) = if command == CMD_SEND_IP_FRAGMENTED_UDP {
        let socket = UdpSocket::bind("127.0.0.1:0").expect("UDP socket");
        socket.connect("127.0.0.1:443").expect("UDP target");
        (socket.into(), None)
    } else {
        let listener = TcpListener::bind("127.0.0.1:0").expect("TCP listener");
        let stream = TcpStream::connect(listener.local_addr().expect("TCP address")).expect("TCP stream");
        let (peer, _) = listener.accept().expect("TCP peer");
        (stream.into(), Some(peer))
    };
    let params = if command == CMD_SEND_IP_FRAGMENTED_UDP {
        serde_json::json!({
            "target_addr":"127.0.0.1:443", "payload":[1,2,3], "split_offset":8, "default_ttl":64,
            "ipv6_hop_by_hop":false, "ipv6_dest_opt":false, "ipv6_dest_opt_fragmentable":false,
            "ipv6_routing":false, "ipv6_second_frag_next_override":null,
        })
    } else if command == CMD_SEND_FAKE_RST {
        serde_json::json!({"default_ttl":64})
    } else {
        serde_json::json!({"payload":[1,2,3],"split_offset":1,"default_ttl":64})
    };
    let result = ClientTransport::new(path.to_string_lossy().into_owned()).send_command_once(
        command,
        params,
        Some(stream.as_fd()),
        "abcdefghijklmnopqrstuvwxyzABCDEF",
        None,
    );
    worker.join().expect("helper fixture");
    std::fs::remove_file(path).expect("remove socket");
    result.expect_err("fragment send failed")
}

#[test]
fn udp_fragment_helper_error_preserves_kind_and_emission_boundary() {
    for kind in [io::ErrorKind::InvalidInput, io::ErrorKind::WouldBlock] {
        for uncertain in [false, true] {
            let error = failure_for(
                CMD_SEND_IP_FRAGMENTED_UDP,
                serde_json::json!({
                    "error_kind": format!("{kind:?}"), "raw_os_error": null, "payload_commit_uncertain": uncertain,
                }),
            );
            assert_eq!(error.kind(), kind);
            let phase = error.get_ref().and_then(|inner| inner.downcast_ref::<IpFragmentSendError>()).expect("phase");
            assert_eq!(phase.payload_commit_uncertain, uncertain);
        }
    }
}

#[test]
fn fragment_helper_error_preserves_busy_kind_and_emission_boundary() {
    for uncertain in [false, true] {
        let error = failure_for(
            CMD_SEND_IP_FRAGMENTED_TCP,
            serde_json::json!({
                "error_kind":"WouldBlock", "raw_os_error":null, "payload_commit_uncertain":uncertain,
            }),
        );
        assert_eq!(error.kind(), io::ErrorKind::WouldBlock);
        let phase = error.get_ref().and_then(|inner| inner.downcast_ref::<IpFragmentSendError>()).expect("phase");
        assert_eq!(phase.payload_commit_uncertain, uncertain);
    }
}

#[test]
fn fragment_helper_error_preserves_normalized_kind_and_original_errno() {
    for command in [CMD_SEND_IP_FRAGMENTED_TCP, CMD_SEND_IP_FRAGMENTED_UDP, CMD_SEND_FAKE_RST] {
        let error = failure_for(
            command,
            serde_json::json!({
                "error_kind":"Unsupported", "raw_os_error":libc::EPERM, "payload_commit_uncertain":false,
            }),
        );
        assert_eq!(error.kind(), io::ErrorKind::Unsupported);
        let phase = error.get_ref().and_then(|inner| inner.downcast_ref::<IpFragmentSendError>()).expect("phase");
        assert!(!phase.payload_commit_uncertain);
        assert_eq!(phase.raw_os_error(), Some(libc::EPERM));
    }
}

#[test]
fn legacy_or_invalid_fragment_error_cannot_claim_a_pre_send_boundary() {
    for command in [CMD_SEND_IP_FRAGMENTED_TCP, CMD_SEND_IP_FRAGMENTED_UDP, CMD_SEND_FAKE_RST] {
        let error = failure_for(command, serde_json::Value::Null);
        assert_eq!(error.kind(), io::ErrorKind::Other);
        assert!(error.get_ref().and_then(|inner| inner.downcast_ref::<IpFragmentSendError>()).is_none());
    }
    for data in [
        serde_json::json!({"error_kind":"WouldBlock"}),
        serde_json::json!({"error_kind":"Invented","raw_os_error":null,"payload_commit_uncertain":false}),
        serde_json::json!({"error_kind":"WouldBlock","raw_os_error":-1,"payload_commit_uncertain":false}),
        serde_json::json!({"error_kind":"WouldBlock","raw_os_error":null,"payload_commit_uncertain":"false"}),
    ] {
        for command in [CMD_SEND_IP_FRAGMENTED_TCP, CMD_SEND_IP_FRAGMENTED_UDP, CMD_SEND_FAKE_RST] {
            let error = failure_for(command, data.clone());
            assert_eq!(error.kind(), io::ErrorKind::InvalidData);
            assert!(error.get_ref().and_then(|inner| inner.downcast_ref::<IpFragmentSendError>()).is_none());
        }
    }
}

#[test]
fn fake_rst_helper_error_preserves_the_socket_restoration_boundary() {
    for uncertain in [false, true] {
        let error = failure_for(
            CMD_SEND_FAKE_RST,
            serde_json::json!({
                "error_kind":"Unsupported", "raw_os_error":null, "payload_commit_uncertain":uncertain,
            }),
        );
        assert_eq!(error.kind(), io::ErrorKind::Unsupported);
        let phase = error.get_ref().and_then(|inner| inner.downcast_ref::<IpFragmentSendError>()).expect("phase");
        assert_eq!(phase.payload_commit_uncertain, uncertain);
    }
}
