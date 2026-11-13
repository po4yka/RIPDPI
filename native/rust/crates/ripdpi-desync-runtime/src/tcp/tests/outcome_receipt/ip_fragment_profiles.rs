use super::*;

use ripdpi_config::{TcpIpFragPayload, TcpIpv6ExtensionPayload};
use ripdpi_ipfrag::{Ipv6ExtHeaders, TcpFragmentSpec, build_tcp_fragment_pair};

fn emit_fragment_pair(
    stream: &TcpStream,
    payload: &[u8],
    split_offset: usize,
    disorder: bool,
    ipv6_ext: Ipv6ExtHeaders,
    flags: TcpFlagOverrides,
) -> io::Result<()> {
    let spec = TcpFragmentSpec {
        src: "[2001:db8::1]:50000".parse().expect("source"),
        dst: "[2001:db8::2]:443".parse().expect("target"),
        ttl: 64,
        identification: 0x1234_5678,
        sequence_number: 1,
        acknowledgment_number: 2,
        window_size: 4096,
        timestamp: None,
        tcp_flags_set: flags.set,
        tcp_flags_unset: flags.unset,
        ipv6_ext,
    };
    let pair = build_tcp_fragment_pair(spec, payload, split_offset).expect("fragment bytes");
    let packets = if disorder { [&pair.second, &pair.first] } else { [&pair.first, &pair.second] };
    for packet in packets {
        (&*stream).write_all(packet)?;
    }
    Ok(())
}

impl TcpFragmentSender for ReceiptTestPlatform {
    fn send_ip_fragmented_tcp(
        &self,
        stream: &TcpStream,
        payload: &[u8],
        split_offset: usize,
        _default_ttl: u8,
        _protect_path: Option<&str>,
        disorder: bool,
        ipv6_ext: ripdpi_ipfrag::Ipv6ExtHeaders,
        flags: TcpFlagOverrides,
        _ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        let emit = {
            let mut faults = self.faults.lock().expect("receipt fault queue");
            if matches!(faults.front(), Some(FaultSpec::EmitIpFragments)) {
                faults.pop_front();
                true
            } else {
                false
            }
        };
        if emit {
            return emit_fragment_pair(stream, payload, split_offset, disorder, ipv6_ext, flags);
        }
        Err(io::Error::new(
            self.ipfrag_error_kind.unwrap_or(io::ErrorKind::Unsupported),
            "ipfrag unsupported in receipt test",
        ))
    }

    fn send_multi_disorder_tcp(
        &self,
        stream: &TcpStream,
        payload: &[u8],
        _segments: &[TcpPayloadSegment],
        _default_ttl: u8,
        _protect_path: Option<&str>,
        _inter_segment_delay_ms: u32,
        _md5sig: bool,
        _original_flags: TcpFlagOverrides,
        _ip_id_mode: Option<ripdpi_config::IpIdMode>,
    ) -> io::Result<()> {
        let fault = self.faults.lock().expect("receipt fault queue").pop_front();
        if let Some(FaultSpec::MultiDisorderAfterPrefix { written, report_progress }) = fault {
            let mut stream = stream;
            stream.write_all(&payload[..written])?;
            let source = io::Error::from(io::ErrorKind::TimedOut);
            return Err(if report_progress {
                io::Error::new(source.kind(), crate::transport_io::WriteProgressError { written, source })
            } else {
                source
            });
        }
        Err(io::Error::new(io::ErrorKind::Unsupported, "multi-disorder unsupported in receipt test"))
    }
}

#[test]
fn ipfrag2_legacy_executor_preserves_extension_profiles_and_fragment_order() {
    let payload = b"GET / HTTP/1.1\r\nHost: example.test\r\n\r\n";
    for (hop_by_hop, dest_opt, dest_opt2) in
        [(false, false, false), (true, false, false), (true, false, true), (false, true, false), (true, true, false)]
    {
        for disorder in [false, true] {
            let mut group = test_group();
            group.actions.tcp_chain.push(
                TcpChainStep::new(TcpChainStepKind::IpFrag2, OffsetExpr::absolute(5)).with_ip_frag_payload(
                    TcpIpFragPayload {
                        fragment_count: 0,
                        min_fragment_size: 0,
                        max_fragment_size: 0,
                        disorder,
                        ipv6_extensions: TcpIpv6ExtensionPayload {
                            hop_by_hop,
                            dest_opt,
                            dest_opt2,
                            ..Default::default()
                        },
                    },
                ),
            );
            let platform = ReceiptTestPlatform::default();
            platform.faults.lock().expect("faults").push_back(FaultSpec::EmitIpFragments);
            let (mut client, mut peer) = connected_pair();
            peer.set_read_timeout(Some(Duration::from_secs(2))).expect("read deadline");
            let progress = OutboundProgress {
                round: 1,
                payload_size: payload.len(),
                stream_start: 0,
                stream_end: payload.len() - 1,
            };
            let context = activation_context_from_progress(
                progress,
                ActivationTransport::Tcp,
                Some(payload),
                None,
                None,
                None,
                AdaptivePlannerHints::default(),
            );
            let outcome = send_prepared_with_group(
                &mut client,
                &platform,
                &RuntimeConfig::default(),
                &group,
                payload,
                progress,
                context,
                None,
                None,
                &default_ttl_unavailable(),
                None,
            )
            .expect("emit fragment bytes through loopback");
            client.shutdown(std::net::Shutdown::Write).expect("close writer");
            let mut bytes = Vec::new();
            peer.read_to_end(&mut bytes).expect("read fragment bytes");
            assert_eq!(outcome.bytes_committed, payload.len());
            let first_len = 40 + usize::from(u16::from_be_bytes([bytes[4], bytes[5]]));
            let packets = [&bytes[..first_len], &bytes[first_len..]];
            let mut transport = vec![0; 20 + payload.len() + usize::from(dest_opt2) * 8];
            for (index, packet) in packets.into_iter().enumerate() {
                let mut cursor = 40;
                let mut next = packet[6];
                for expected in [hop_by_hop.then_some(0), dest_opt.then_some(60)].into_iter().flatten() {
                    assert_eq!(next, expected);
                    next = packet[cursor];
                    cursor += (usize::from(packet[cursor + 1]) + 1) * 8;
                }
                assert_eq!(next, 44);
                assert_eq!(packet[cursor], if dest_opt2 { 60 } else { 6 });
                let control = u16::from_be_bytes([packet[cursor + 2], packet[cursor + 3]]);
                let offset = usize::from(control & !7);
                assert_eq!(offset == 0, (index == 0) != disorder);
                let fragment = &packet[cursor + 8..];
                transport[offset..offset + fragment.len()].copy_from_slice(fragment);
            }
            let tcp_start = if dest_opt2 {
                assert_eq!(&transport[..2], &[6, 0]);
                8
            } else {
                0
            };
            let tcp_header_len = usize::from(transport[tcp_start + 12] >> 4) * 4;
            assert_eq!(&transport[tcp_start + tcp_header_len..], payload);
        }
    }
}
