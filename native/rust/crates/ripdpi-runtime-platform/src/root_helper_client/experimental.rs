use std::io;
use std::net::SocketAddr;

use ripdpi_root_helper_protocol::{
    CMD_RECV_ICMP_WRAPPED_UDP, CMD_SEND_ICMP_WRAPPED_UDP, CMD_SEND_RAW_IP_PACKET, CMD_SEND_SYN_HIDE_TCP,
    RawIpPacketParams,
};

use super::RootHelperClient;
use crate::experimental::{IcmpWrappedUdpRecvFilter, IcmpWrappedUdpSpec, ReceivedIcmpWrappedUdp, SynHideTcpSpec};

impl RootHelperClient {
    pub fn send_syn_hide_tcp(&self, spec: SynHideTcpSpec) -> io::Result<()> {
        let params = serde_json::to_value(spec)
            .map_err(|error| io::Error::other(format!("serialize syn hide spec: {error}")))?;
        let (_resp, _fd) = self.transport.send_command(CMD_SEND_SYN_HIDE_TCP, params, None)?;
        Ok(())
    }

    pub fn send_icmp_wrapped_udp(&self, spec: &IcmpWrappedUdpSpec) -> io::Result<()> {
        let params = serde_json::to_value(spec)
            .map_err(|error| io::Error::other(format!("serialize ICMP-wrapped UDP spec: {error}")))?;
        let (_resp, _fd) = self.transport.send_command(CMD_SEND_ICMP_WRAPPED_UDP, params, None)?;
        Ok(())
    }

    pub fn recv_icmp_wrapped_udp(&self, filter: IcmpWrappedUdpRecvFilter) -> io::Result<ReceivedIcmpWrappedUdp> {
        let params = serde_json::to_value(filter)
            .map_err(|error| io::Error::other(format!("serialize ICMP-wrapped UDP filter: {error}")))?;
        let (resp, _fd) = self.transport.send_command(CMD_RECV_ICMP_WRAPPED_UDP, params, None)?;
        serde_json::from_value(resp.data).map_err(|error| {
            io::Error::new(io::ErrorKind::InvalidData, format!("invalid ICMP-wrapped UDP reply: {error}"))
        })
    }

    pub fn send_raw_ip_packet(&self, target: SocketAddr, packet: &[u8]) -> io::Result<()> {
        let params =
            serde_json::to_value(RawIpPacketParams { target_addr: target.to_string(), packet: packet.to_vec() })
                .map_err(|error| io::Error::other(format!("serialize raw IP packet params: {error}")))?;
        let (_resp, _fd) = self.transport.send_command(CMD_SEND_RAW_IP_PACKET, params, None)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use std::net::{Ipv4Addr, SocketAddr};
    use std::os::unix::net::UnixListener;
    use std::thread;

    use ripdpi_root_helper_protocol::{
        CAPABILITY_VERSION, CMD_PROTOCOL_PREFLIGHT, CMD_SEND_RAW_IP_PACKET, HelperRequest, HelperResponse,
        PROTOCOL_VERSION, recv_message, send_message,
    };

    use super::RootHelperClient;

    #[test]
    fn send_raw_ip_packet_uses_root_helper_command() {
        let socket_path = std::env::temp_dir().join(format!(
            "ripdpi-root-helper-raw-{}-{}.sock",
            std::process::id(),
            unique_suffix()
        ));
        let _ = std::fs::remove_file(&socket_path);
        let nonce_path = format!("{}.nonce", socket_path.to_string_lossy());
        std::fs::write(&nonce_path, test_session_nonce()).expect("write nonce");
        let listener = UnixListener::bind(&socket_path).expect("bind helper socket");
        let server = thread::spawn(move || {
            let (stream, _) = listener.accept().expect("accept protocol preflight");
            let (payload, fd) = recv_message(&stream, "client closed").expect("preflight request");
            assert!(fd.is_none());
            let request: HelperRequest = serde_json::from_slice(&payload).expect("preflight JSON");
            assert_eq!(request.command, CMD_PROTOCOL_PREFLIGHT);
            assert_eq!(request.session_nonce.as_deref(), Some(test_session_nonce()));
            let response = serde_json::to_vec(
                &HelperResponse::success(serde_json::Value::Null).with_versions(PROTOCOL_VERSION, CAPABILITY_VERSION),
            )
            .expect("preflight response JSON");
            send_message(&stream, &response, None).expect("preflight response");

            let (stream, _) = listener.accept().expect("accept helper client");
            let (payload, fd) = recv_message(&stream, "client closed").expect("request");
            assert!(fd.is_none());
            let request: HelperRequest = serde_json::from_slice(&payload).expect("request JSON");
            assert_eq!(request.command, CMD_SEND_RAW_IP_PACKET);
            assert_eq!(request.session_nonce.as_deref(), Some(test_session_nonce()));
            assert_eq!(request.params["target_addr"], "203.0.113.10:443");
            assert_eq!(request.params["packet"], serde_json::json!([69, 0, 0, 20]));
            let response = serde_json::to_vec(
                &HelperResponse::success(serde_json::Value::Null).with_versions(PROTOCOL_VERSION, CAPABILITY_VERSION),
            )
            .expect("response JSON");
            send_message(&stream, &response, None).expect("response");
        });

        let client = RootHelperClient::new(socket_path.to_string_lossy().to_string());
        client
            .send_raw_ip_packet(SocketAddr::from((Ipv4Addr::new(203, 0, 113, 10), 443)), &[0x45, 0x00, 0x00, 0x14])
            .expect("send raw IP packet");

        server.join().expect("server thread");
        let _ = std::fs::remove_file(socket_path);
        let _ = std::fs::remove_file(nonce_path);
    }

    fn unique_suffix() -> u128 {
        std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).expect("clock").as_nanos()
    }

    fn test_session_nonce() -> &'static str {
        "abcdefghijklmnopqrstuvwxyzABCDEF"
    }
}
