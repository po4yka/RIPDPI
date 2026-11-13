#![cfg(not(feature = "loom"))]

use std::io::Read;
use std::net::{Shutdown, TcpListener, TcpStream};
use std::sync::atomic::AtomicBool;
use std::time::Duration;

use ripdpi_config::{DesyncGroup, OffsetExpr, RuntimeConfig, TcpChainStep, TcpChainStepKind};
use ripdpi_desync::{ActivationTransport, AdaptivePlannerHints};
use ripdpi_desync_runtime::activation_context_from_progress;
use ripdpi_proxy_runtime_desync_adapter::desync_platform::send_prepared_with_runtime_platform;
use ripdpi_session::OutboundProgress;

#[test]
fn tls_record_split_reaches_a_real_loopback_peer_with_unchanged_handshake_bytes() {
    let cases: &[(&[u8], usize)] = &[
        (include_bytes!("../../ripdpi-packets/tests/fixtures/curl_tls12_example_com.bin"), 111),
        (include_bytes!("../../ripdpi-packets/tests/fixtures/curl_tls13_example_com.bin"), 95),
        (include_bytes!("../../ripdpi-packets/src/fake_profiles/tls_clienthello_www_google_com.bin"), 121),
    ];
    for &(source, marker) in cases {
        let original = &source[5..];
        let split = marker + 1;
        let mut input = Vec::new();
        for part in [&original[..split], &original[split..]] {
            input.extend_from_slice(&source[..3]);
            input.extend_from_slice(&u16::try_from(part.len()).unwrap().to_be_bytes());
            input.extend_from_slice(part);
        }
        let mut group = DesyncGroup::new(0);
        group.actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::TlsRec, OffsetExpr::host(1)));
        let listener = TcpListener::bind("127.0.0.1:0").expect("loopback listener");
        let mut sender = TcpStream::connect(listener.local_addr().unwrap()).expect("loopback sender");
        let (mut peer, _) = listener.accept().expect("loopback peer");
        sender.set_write_timeout(Some(Duration::from_secs(3))).unwrap();
        peer.set_read_timeout(Some(Duration::from_secs(3))).unwrap();
        let progress =
            OutboundProgress { round: 1, payload_size: input.len(), stream_start: 0, stream_end: input.len() - 1 };
        let context = activation_context_from_progress(
            progress,
            ActivationTransport::Tcp,
            Some(&input),
            None,
            None,
            None,
            AdaptivePlannerHints::default(),
        );
        let outcome = send_prepared_with_runtime_platform(
            &mut sender,
            &RuntimeConfig::default(),
            &group,
            &input,
            progress,
            context,
            None,
            None,
            &AtomicBool::new(false),
            None,
        )
        .expect("production adapter send");
        sender.shutdown(Shutdown::Write).unwrap();
        let mut wire = Vec::new();
        peer.read_to_end(&mut wire).expect("read emitted stream");
        assert_eq!(outcome.execution_receipt.tls_prelude_applied_count, 1);
        assert_eq!(wire.len(), input.len() + 5);
        assert_eq!(outcome.bytes_committed, wire.len());
        let mut remaining = wire.as_slice();
        let mut payload = Vec::new();
        let mut lengths = Vec::new();
        while !remaining.is_empty() {
            assert_eq!(&remaining[..3], &source[..3]);
            let length = usize::from(u16::from_be_bytes([remaining[3], remaining[4]]));
            assert!((1..=16_384).contains(&length));
            let (fragment, rest) = remaining[5..].split_at(length);
            payload.extend_from_slice(fragment);
            lengths.push(length);
            remaining = rest;
        }
        assert_eq!(lengths, vec![marker, 1, original.len() - split]);
        assert_eq!(payload, original);
    }
}
