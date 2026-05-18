/// Request the helper to probe what privileged capabilities are available.
///
/// Response `data` shape (all fields are JSON booleans):
/// ```json
/// { "raw_ipv4": true, "raw_ipv6": false, "tcp_repair": true }
/// ```
/// - `raw_ipv4`  - helper can open `AF_INET  / SOCK_RAW` sockets.
/// - `raw_ipv6`  - helper can open `AF_INET6 / SOCK_RAW` sockets.
/// - `tcp_repair` - helper can set `TCP_REPAIR` socket option.
///
/// Runtime-side conversion from this JSON shape to typed runtime capability
/// outcomes lives in `ripdpi-runtime`, where those capability types are defined.
pub const CMD_PROBE_CAPABILITIES: &str = "probe_capabilities";
pub const CMD_SEND_FAKE_TCP: &str = "send_fake_tcp";
pub const CMD_SEND_FAKE_RST: &str = "send_fake_rst";
pub const CMD_SEND_FLAGGED_TCP_PAYLOAD: &str = "send_flagged_tcp_payload";
pub const CMD_SEND_SEQOVL_TCP: &str = "send_seqovl_tcp";
pub const CMD_SEND_MULTI_DISORDER_TCP: &str = "send_multi_disorder_tcp";
pub const CMD_SEND_ORDERED_TCP_SEGMENTS: &str = "send_ordered_tcp_segments";
pub const CMD_SEND_IP_FRAGMENTED_TCP: &str = "send_ip_fragmented_tcp";
pub const CMD_SEND_IP_FRAGMENTED_UDP: &str = "send_ip_fragmented_udp";
pub const CMD_SEND_SYN_HIDE_TCP: &str = "send_syn_hide_tcp";
pub const CMD_SEND_ICMP_WRAPPED_UDP: &str = "send_icmp_wrapped_udp";
pub const CMD_RECV_ICMP_WRAPPED_UDP: &str = "recv_icmp_wrapped_udp";
pub const CMD_SEND_RAW_IP_PACKET: &str = "send_raw_ip_packet";
pub const CMD_SHUTDOWN: &str = "shutdown";
