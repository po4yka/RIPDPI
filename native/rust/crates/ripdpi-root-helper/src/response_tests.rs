use std::os::unix::net::UnixStream;
use std::time::{Duration, Instant};

use ripdpi_privileged_ops::{IcmpWrappedUdpRole, ReceivedIcmpWrappedUdp};
use ripdpi_root_helper_protocol::{CMD_RECV_ICMP_WRAPPED_UDP, HelperResponse, recv_response_message_with_deadline};

#[test]
fn icmp_receive_response_preserves_payload_and_bounded_metadata() {
    let message = ReceivedIcmpWrappedUdp {
        peer: "[ffff:ffff:ffff:ffff:ffff:ffff:ffff:ffff%4294967295]:65535".parse().expect("peer"),
        service_port: u16::MAX,
        payload: vec![255; 65_493],
        session_id: u32::MAX,
        icmp_code: u8::MAX,
        role: IcmpWrappedUdpRole::ServerReply,
        xor_payload: true,
    };
    let expected = message.clone();
    let response = HelperResponse::success(serde_json::to_value(message).expect("message"));
    let (sender, receiver) = UnixStream::pair().expect("IPC pair");
    let writer = std::thread::spawn(move || super::send_response(&sender, CMD_RECV_ICMP_WRAPPED_UDP, response, None));
    let (bytes, fd) = recv_response_message_with_deadline(
        &receiver,
        CMD_RECV_ICMP_WRAPPED_UDP,
        "closed",
        Instant::now() + Duration::from_secs(2),
    )
    .expect("full response");
    assert!(fd.is_none());
    let response: HelperResponse = serde_json::from_slice(&bytes).expect("response JSON");
    response.validate_protocol_version().expect("exact protocol version");
    let received: ReceivedIcmpWrappedUdp = serde_json::from_value(response.data).expect("message JSON");
    assert_eq!(received, expected);
    writer.join().expect("writer").expect("send response");
}
