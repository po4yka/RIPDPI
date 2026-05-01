use ripdpi_packets::{is_quic_initial, parse_quic_initial, tls_marker_info};
use ripdpi_runtime_policy::runtime_policy::is_tls_client_hello_payload;

pub(crate) struct TcpPayloadClassification {
    pub(crate) is_tls: bool,
    pub(crate) has_ech: bool,
}

pub(crate) struct UdpPayloadClassification {
    pub(crate) is_quic: bool,
    pub(crate) has_ech: bool,
}

pub(crate) fn classify_tcp_payload(payload: &[u8]) -> TcpPayloadClassification {
    let is_tls = is_tls_client_hello_payload(payload);
    let has_ech = is_tls && tls_marker_info(payload).and_then(|markers| markers.ech_ext_start).is_some();
    TcpPayloadClassification { is_tls, has_ech }
}

pub(crate) fn classify_udp_payload(payload: &[u8]) -> UdpPayloadClassification {
    let parsed_quic = parse_quic_initial(payload);
    let has_ech = parsed_quic.as_ref().and_then(|info| info.tls_info.ech_ext_start).is_some();
    let is_quic = is_quic_initial(payload);
    UdpPayloadClassification { is_quic, has_ech }
}
