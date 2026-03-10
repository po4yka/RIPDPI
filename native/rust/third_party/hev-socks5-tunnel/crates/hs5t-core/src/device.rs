use smoltcp::phy::{ChecksumCapabilities, Device, DeviceCapabilities, Medium, RxToken, TxToken};
use smoltcp::time::Instant;
use std::collections::VecDeque;

/// A smoltcp `Device` implementation backed by two in-memory packet queues.
///
/// - `rx_queue`: packets waiting to be consumed by smoltcp (injected externally).
/// - `tx_queue`: packets produced by smoltcp, pending write to the TUN fd.
///
/// The TUN fd is owned by `io_loop_task`, not by this device.  All TUN
/// reads/writes happen in `io_loop_task` code; this struct only mediates between
/// the raw byte streams and smoltcp's internal state machines.
pub struct TunDevice {
    pub rx_queue: VecDeque<Vec<u8>>,
    pub tx_queue: VecDeque<Vec<u8>>,
    pub mtu: usize,
}

impl TunDevice {
    pub fn new(mtu: usize) -> Self {
        Self {
            rx_queue: VecDeque::new(),
            tx_queue: VecDeque::new(),
            mtu,
        }
    }
}

// ── smoltcp RxToken ───────────────────────────────────────────────────────────

/// Owned receive token: holds one packet from `rx_queue`.
pub struct OwnedRxToken(Vec<u8>);

impl RxToken for OwnedRxToken {
    fn consume<R, F>(self, f: F) -> R
    where
        F: FnOnce(&[u8]) -> R,
    {
        f(&self.0)
    }
}

// ── smoltcp TxToken ───────────────────────────────────────────────────────────

/// Transmit token: pushes the constructed packet into `tx_queue` on consume.
pub struct OwnedTxToken<'a>(&'a mut VecDeque<Vec<u8>>);

impl<'a> TxToken for OwnedTxToken<'a> {
    fn consume<R, F>(self, len: usize, f: F) -> R
    where
        F: FnOnce(&mut [u8]) -> R,
    {
        let mut buf = vec![0u8; len];
        let result = f(&mut buf);
        self.0.push_back(buf);
        result
    }
}

// ── smoltcp Device impl ───────────────────────────────────────────────────────

impl Device for TunDevice {
    type RxToken<'a>
        = OwnedRxToken
    where
        Self: 'a;
    type TxToken<'a>
        = OwnedTxToken<'a>
    where
        Self: 'a;

    fn receive(&mut self, _timestamp: Instant) -> Option<(Self::RxToken<'_>, Self::TxToken<'_>)> {
        let pkt = self.rx_queue.pop_front()?;
        Some((OwnedRxToken(pkt), OwnedTxToken(&mut self.tx_queue)))
    }

    fn transmit(&mut self, _timestamp: Instant) -> Option<Self::TxToken<'_>> {
        Some(OwnedTxToken(&mut self.tx_queue))
    }

    fn capabilities(&self) -> DeviceCapabilities {
        let mut caps = DeviceCapabilities::default();
        caps.medium = Medium::Ip;
        caps.max_transmission_unit = self.mtu;
        // Disable checksum verification so tests can inject hand-crafted packets
        // without computing correct checksums.
        caps.checksum = ChecksumCapabilities::ignored();
        caps
    }
}

// ── Test helpers ──────────────────────────────────────────────────────────────

/// Build a raw IPv4/TCP SYN packet (no payload, no checksum).
///
/// `caps.checksum = ChecksumCapabilities::ignored()` means smoltcp won't
/// reject it due to a bad checksum.
pub fn build_tcp_syn(src_ip: [u8; 4], dst_ip: [u8; 4], src_port: u16, dst_port: u16) -> Vec<u8> {
    let mut pkt = vec![0u8; 40]; // 20-byte IPv4 header + 20-byte TCP header
                                 // IPv4 header
    pkt[0] = 0x45; // version=4, IHL=5
    pkt[2] = 0;
    pkt[3] = 40; // total length = 40
    pkt[4] = 0x00;
    pkt[5] = 0x01; // ID
    pkt[6] = 0x40;
    pkt[7] = 0x00; // DF flag
    pkt[8] = 64; // TTL
    pkt[9] = 6; // Protocol: TCP
                // Checksum: 0 (ignored by smoltcp when caps.checksum = ignored)
    pkt[12..16].copy_from_slice(&src_ip);
    pkt[16..20].copy_from_slice(&dst_ip);
    // TCP header at offset 20
    pkt[20..22].copy_from_slice(&src_port.to_be_bytes());
    pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
    // Seq = 0, Ack = 0 (bytes 24..32 = 0)
    pkt[32] = 0x50; // data offset = 5 (20 bytes)
    pkt[33] = 0x02; // SYN flag
    pkt[34] = 0xff;
    pkt[35] = 0xff; // window = 65535
    pkt
}

/// Build an IPv4/TCP ACK packet given the SYN-ACK's seq number.
///
/// `ack_seq` is the `seq_number` from the SYN-ACK (we ack seq+1).
pub fn build_tcp_ack(
    src_ip: [u8; 4],
    dst_ip: [u8; 4],
    src_port: u16,
    dst_port: u16,
    seq: u32,
    ack: u32,
) -> Vec<u8> {
    let mut pkt = vec![0u8; 40];
    pkt[0] = 0x45;
    pkt[2] = 0;
    pkt[3] = 40;
    pkt[8] = 64;
    pkt[9] = 6;
    pkt[12..16].copy_from_slice(&src_ip);
    pkt[16..20].copy_from_slice(&dst_ip);
    pkt[20..22].copy_from_slice(&src_port.to_be_bytes());
    pkt[22..24].copy_from_slice(&dst_port.to_be_bytes());
    pkt[24..28].copy_from_slice(&seq.to_be_bytes()); // seq
    pkt[28..32].copy_from_slice(&ack.to_be_bytes()); // ack_seq
    pkt[32] = 0x50; // data offset
    pkt[33] = 0x10; // ACK flag
    pkt[34] = 0xff;
    pkt[35] = 0xff;
    pkt
}

/// Extract seq and ack numbers from a raw IPv4/TCP packet.
///
/// Returns `(seq, ack_seq)`.  Panics if the packet is too short.
pub fn tcp_seq_ack(pkt: &[u8]) -> (u32, u32) {
    let ihl = ((pkt[0] & 0x0f) as usize) * 4;
    let seq = u32::from_be_bytes([pkt[ihl + 4], pkt[ihl + 5], pkt[ihl + 6], pkt[ihl + 7]]);
    let ack = u32::from_be_bytes([pkt[ihl + 8], pkt[ihl + 9], pkt[ihl + 10], pkt[ihl + 11]]);
    (seq, ack)
}

#[cfg(test)]
mod tests {
    use super::*;
    use smoltcp::iface::{Config as IfaceConfig, Interface, SocketSet};
    use smoltcp::socket::tcp::{self, Socket as TcpSocket};
    use smoltcp::wire::{HardwareAddress, IpAddress, IpCidr};

    fn make_interface(device: &mut TunDevice) -> Interface {
        let config = IfaceConfig::new(HardwareAddress::Ip);
        let mut iface = Interface::new(config, device, Instant::now());
        iface.update_ip_addrs(|addrs| {
            addrs
                .push(IpCidr::new(IpAddress::v4(127, 0, 0, 1), 8))
                .unwrap();
        });
        iface.set_any_ip(true);
        iface
    }

    fn make_tcp_socket() -> TcpSocket<'static> {
        TcpSocket::new(
            tcp::SocketBuffer::new(vec![0u8; 65536]),
            tcp::SocketBuffer::new(vec![0u8; 65536]),
        )
    }

    /// U-01: Inject a raw IPv4/TCP SYN packet into rx_queue, call iface.poll(),
    ///        verify that smoltcp produces a SYN-ACK in tx_queue.
    #[test]
    fn u01_rx_packet_causes_smoltcp_response() {
        let mut device = TunDevice::new(1500);
        let mut iface = make_interface(&mut device);
        let mut socket_set = SocketSet::new(vec![]);

        let mut sock = make_tcp_socket();
        sock.listen(80).unwrap();
        socket_set.add(sock);

        // Inject TCP SYN: src=10.0.0.1:12345, dst=127.0.0.1:80
        let syn = build_tcp_syn([10, 0, 0, 1], [127, 0, 0, 1], 12345, 80);
        device.rx_queue.push_back(syn);

        // Poll smoltcp
        iface.poll(Instant::now(), &mut device, &mut socket_set);

        // smoltcp must have produced a SYN-ACK response
        assert!(
            !device.tx_queue.is_empty(),
            "smoltcp must produce a SYN-ACK packet in tx_queue"
        );
    }

    /// U-02: After completing 3WHS, smoltcp transmits data → appears in tx_queue.
    ///
    /// Establishes a connection via manual SYN→SYN-ACK→ACK sequence, then
    /// calls `send_slice` on the TcpSocket and verifies that smoltcp outputs
    /// a data packet into tx_queue.
    #[test]
    fn u02_smoltcp_transmit_data_appears_in_tx_queue() {
        let mut device = TunDevice::new(1500);
        let mut iface = make_interface(&mut device);
        let mut socket_set = SocketSet::new(vec![]);

        let mut sock = make_tcp_socket();
        sock.listen(80).unwrap();
        let handle = socket_set.add(sock);

        let src_ip = [10, 0, 0, 1];
        let dst_ip = [127, 0, 0, 1];
        let src_port = 12345u16;
        let dst_port = 80u16;

        // Step 1: SYN
        let syn = build_tcp_syn(src_ip, dst_ip, src_port, dst_port);
        device.rx_queue.push_back(syn);
        iface.poll(Instant::now(), &mut device, &mut socket_set);

        // Step 2: SYN-ACK from smoltcp
        let syn_ack = device
            .tx_queue
            .pop_front()
            .expect("smoltcp must send SYN-ACK");
        let (server_seq, _client_ack) = tcp_seq_ack(&syn_ack);

        // Step 3: ACK from client (acks server_seq + 1)
        let ack = build_tcp_ack(src_ip, dst_ip, src_port, dst_port, 1, server_seq + 1);
        device.rx_queue.push_back(ack);
        iface.poll(Instant::now(), &mut device, &mut socket_set);
        device.tx_queue.clear(); // discard any smoltcp-generated ACKs

        // Step 4: Write data via smoltcp send_slice
        {
            let tcp = socket_set.get_mut::<TcpSocket>(handle);
            assert!(tcp.is_open(), "socket must be ESTABLISHED after 3WHS");
            tcp.send_slice(b"hello").expect("send_slice must succeed");
        }

        // Step 5: Poll to flush the data into tx_queue
        iface.poll(Instant::now(), &mut device, &mut socket_set);

        assert!(
            !device.tx_queue.is_empty(),
            "smoltcp must produce a data segment in tx_queue after send_slice"
        );
    }
}
