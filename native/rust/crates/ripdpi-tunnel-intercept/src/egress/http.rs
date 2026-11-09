use std::collections::HashMap;
use std::net::SocketAddr;

use super::packet::{PacketMeta, Transport};

const MAX_TRACKED_TCP_FLOWS: usize = 1024;

#[derive(Default)]
pub(super) struct HttpFirstPayloads {
    flows: HashMap<(SocketAddr, SocketAddr), FirstPayload>,
}

struct FirstPayload {
    sequence: u32,
    eligible: Option<bool>,
}

impl HttpFirstPayloads {
    pub(super) fn inspect(&mut self, meta: PacketMeta, packet: &[u8]) -> bool {
        if meta.transport != Transport::Tcp {
            return false;
        }
        let key = (meta.src_addr(), meta.dst_addr());
        let Some(sequence) = meta.tcp_payload_sequence(packet) else {
            return false;
        };
        if meta.is_tcp_syn(packet) {
            if !self.flows.contains_key(&key) && self.flows.len() >= MAX_TRACKED_TCP_FLOWS {
                return false;
            }
            let first = self.flows.entry(key).or_insert(FirstPayload { sequence, eligible: None });
            if first.sequence != sequence {
                *first = FirstPayload { sequence, eligible: None };
            }
        }
        let payload = meta.payload(packet);
        if payload.is_empty() {
            return false;
        }
        let Some(first) = self.flows.get_mut(&key) else {
            // Midstream payloads can be HTTP-looking body bytes of any protocol.
            return false;
        };
        if sequence != first.sequence {
            first.eligible = Some(false);
            return false;
        }
        let complete_head = ripdpi_packets::http_request_head_end(payload).is_some();
        let eligible = *first.eligible.get_or_insert(complete_head);
        eligible && complete_head
    }

    pub(super) fn close(&mut self, src: SocketAddr, dst: SocketAddr) {
        self.flows.remove(&(src, dst));
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    const HEAD: &[u8] = b"GET / HTTP/1.1\r\nHost: example.com\r\n\r\n";

    fn inspect(flows: &mut HttpFirstPayloads, port: u16, sequence: u32, syn: bool, payload: &[u8]) -> bool {
        let mut packet = Vec::new();
        let builder =
            etherparse::PacketBuilder::ipv4([10, 0, 0, 2], [93, 184, 216, 34], 64).tcp(port, 80, sequence, 65535);
        if syn {
            builder.syn().write(&mut packet, payload).unwrap();
        } else {
            builder.ack(0).write(&mut packet, payload).unwrap();
        }
        flows.inspect(PacketMeta::parse(&packet).unwrap(), &packet)
    }

    #[test]
    fn capacity_does_not_evict_an_existing_flow_or_accept_midstream_data() {
        let mut flows = HttpFirstPayloads::default();
        for port in 10_000..10_000 + MAX_TRACKED_TCP_FLOWS as u16 {
            assert!(!inspect(&mut flows, port, 100, true, b""));
        }
        assert!(!inspect(&mut flows, 20_000, 100, true, b""));
        assert!(!inspect(&mut flows, 20_000, 101, false, HEAD));
        assert!(inspect(&mut flows, 10_000, 101, false, HEAD));
        flows.close("10.0.0.2:10000".parse().unwrap(), "93.184.216.34:80".parse().unwrap());
        assert!(!inspect(&mut flows, 20_000, 100, true, b""));
        assert!(inspect(&mut flows, 20_000, 101, false, HEAD));
    }

    #[test]
    fn retransmitted_syn_does_not_restart_a_failed_http_attempt() {
        let mut flows = HttpFirstPayloads::default();
        assert!(!inspect(&mut flows, 10_000, 100, true, b""));
        assert!(!inspect(&mut flows, 10_000, 101, false, b"GET / HTTP/1.1\r\n"));
        assert!(!inspect(&mut flows, 10_000, 100, true, b""));
        assert!(!inspect(&mut flows, 10_000, 101, false, HEAD));
        assert!(!inspect(&mut flows, 10_000, 200, true, b""));
        assert!(inspect(&mut flows, 10_000, 201, false, HEAD));
    }

    #[test]
    fn sequence_progress_permanently_closes_the_initial_retransmission_window() {
        let mut flows = HttpFirstPayloads::default();
        assert!(!inspect(&mut flows, 10_000, 100, true, b""));
        assert!(inspect(&mut flows, 10_000, 101, false, HEAD));
        assert!(inspect(&mut flows, 10_000, 101, false, HEAD));
        assert!(!inspect(&mut flows, 10_000, 1000, false, b"body"));
        // The same u32 sequence can recur after the stream crosses 2^32 bytes.
        assert!(!inspect(&mut flows, 10_000, 101, false, HEAD));
        assert!(!inspect(&mut flows, 10_000, 100, true, b""));
        assert!(!inspect(&mut flows, 10_000, 101, false, HEAD));
    }

    #[test]
    fn syn_data_and_wrapped_initial_sequence_are_supported() {
        let mut flows = HttpFirstPayloads::default();
        assert!(inspect(&mut flows, 10_000, u32::MAX, true, HEAD));
        assert!(inspect(&mut flows, 10_000, 0, false, HEAD));
        assert!(!inspect(&mut flows, 10_000, HEAD.len() as u32, false, HEAD));
    }
}
