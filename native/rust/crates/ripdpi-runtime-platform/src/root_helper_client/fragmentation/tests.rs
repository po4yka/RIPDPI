use std::io::{Read, Write};
use std::net::{TcpListener, TcpStream};
use std::os::fd::AsFd;
use std::os::unix::net::UnixListener;

use ripdpi_root_helper_protocol::{
    CAPABILITY_VERSION, CMD_PROTOCOL_PREFLIGHT, CMD_SEND_IP_FRAGMENTED_TCP, HelperRequest, HelperResponse,
    PROTOCOL_VERSION, recv_message, send_message,
};

use super::{RootHelperClient, TcpFlagOverrides};

#[test]
fn tcp_fragment_request_carries_extensions_and_passes_the_live_socket() {
    let unique = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).expect("clock").as_nanos();
    let path = std::env::temp_dir().join(format!("rhs-f-{}-{unique}.sock", std::process::id()));
    let nonce_path = path.with_extension("sock.nonce");
    std::fs::write(&nonce_path, "abcdefghijklmnopqrstuvwxyzABCDEF").expect("session nonce");
    let listener = UnixListener::bind(&path).expect("helper listener");
    let worker = std::thread::spawn(move || {
        for preflight in [true, false] {
            let (ipc, _) = listener.accept().expect("helper request");
            let (bytes, fd) = recv_message(&ipc, "client closed").expect("request bytes");
            let request: HelperRequest = serde_json::from_slice(&bytes).expect("request JSON");
            assert_eq!(request.protocol_version, Some(4));
            if preflight {
                assert_eq!(request.command, CMD_PROTOCOL_PREFLIGHT);
                assert!(fd.is_none());
            } else {
                assert_eq!(request.command, CMD_SEND_IP_FRAGMENTED_TCP);
                assert_eq!(request.params["disorder"], true);
                for key in ["ipv6_hop_by_hop", "ipv6_dest_opt", "ipv6_dest_opt_fragmentable", "ipv6_routing"] {
                    assert_eq!(request.params[key], true, "missing {key}");
                }
                assert_eq!(request.params["ipv6_second_frag_next_override"], 59);
                assert_eq!(request.params["tcp_flags_set"], 4);
                assert_eq!(request.params["ipv4_identification"], 513);
                let mut passed = TcpStream::from(fd.expect("live TCP descriptor"));
                passed.write_all(b"preserved").expect("send through passed descriptor");
            }
            let response =
                HelperResponse::success(serde_json::Value::Null).with_versions(PROTOCOL_VERSION, CAPABILITY_VERSION);
            send_message(&ipc, &serde_json::to_vec(&response).expect("response"), None).expect("reply");
        }
    });
    let tcp = TcpListener::bind("127.0.0.1:0").expect("TCP listener");
    let stream = TcpStream::connect(tcp.local_addr().expect("TCP address")).expect("TCP client");
    let (mut peer, _) = tcp.accept().expect("TCP peer");
    peer.set_read_timeout(Some(std::time::Duration::from_secs(2))).expect("read deadline");
    let replacement = RootHelperClient::new(path.to_string_lossy().into_owned())
        .send_ip_fragmented_tcp_with_ipv6_ext(
            stream.as_fd(),
            b"request bytes",
            5,
            64,
            true,
            ripdpi_ipfrag::Ipv6ExtHeaders {
                hop_by_hop: true,
                dest_opt: true,
                dest_opt_fragmentable: true,
                routing: true,
                second_frag_next_override: Some(59),
            },
            TcpFlagOverrides { set: 4, unset: 8 },
            Some(513),
        )
        .expect("fragment request");
    assert!(replacement.is_none());
    let mut received = [0; 9];
    peer.read_exact(&mut received).expect("receive passed-socket bytes");
    assert_eq!(&received, b"preserved");
    worker.join().expect("helper fixture");
    std::fs::remove_file(path).expect("remove socket");
    std::fs::remove_file(nonce_path).expect("remove nonce");
}
