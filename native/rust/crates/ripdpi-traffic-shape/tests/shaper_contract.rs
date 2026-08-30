use std::{
    pin::Pin,
    task::{Context, Poll},
    time::Duration,
};

use futures::task::noop_waker_ref;
use ripdpi_traffic_shape::{OpusVoip, Shaper, TrafficShapeProfile, WebRtcVideo};
use tokio::{
    io::{AsyncRead, AsyncReadExt, AsyncWriteExt, ReadBuf, duplex},
    task::JoinSet,
    time::Instant,
};

const FRAME_SIZE: usize = 200;
const TICKS: usize = 1_000;

/// # Cancel safety
///
/// cancel-safe: the test owns both cooperative endpoints and all relay I/O, so cancellation
/// drops the entire in-memory session without externally visible partial state.
#[tokio::test(flavor = "current_thread", start_paused = true)]
async fn opus_voip_reverse_path_is_fixed_rate_fixed_size_and_lossless() {
    let (left_wire, mut left_relay) = duplex(FRAME_SIZE * 4);
    let (mut right_relay, right_wire) = duplex(FRAME_SIZE * 4);
    let mut left = OpusVoip.wrap(left_wire);
    let mut right = OpusVoip.wrap(right_wire);
    let payload = b"reverse-path payload survives fixed-size padding";

    let relay = async move {
        let started = Instant::now();
        let mut frame_sizes = Vec::with_capacity(TICKS);
        let mut frame_times = Vec::with_capacity(TICKS);

        for _ in 0..TICKS {
            let mut frame = [0_u8; FRAME_SIZE];
            right_relay.read_exact(&mut frame).await.unwrap();
            frame_sizes.push(frame.len());
            frame_times.push(Instant::now());
            left_relay.write_all(&frame).await.unwrap();
        }

        (frame_sizes, frame_times, started.elapsed())
    };
    let application = async {
        right.write_all(payload).await.unwrap();
        let mut received = vec![0_u8; payload.len()];
        left.read_exact(&mut received).await.unwrap();
        received
    };
    let ((frame_sizes, frame_times, elapsed), received) = tokio::join!(relay, application);
    assert_eq!(received, payload, "padding/framing must be transparent to the reverse payload");
    assert!(frame_sizes.iter().all(|size| *size == FRAME_SIZE), "every transmitted frame must be exactly 200 bytes");
    assert!(
        frame_times.windows(2).all(|times| times[1].duration_since(times[0]) == Duration::from_millis(20)),
        "every adjacent Opus frame must stay on the 20 ms clock"
    );

    let target = Duration::from_millis(20 * TICKS as u64);
    let tolerance = target.mul_f64(0.05);
    assert!(
        elapsed >= target - tolerance && elapsed <= target + tolerance,
        "1,000 Opus ticks must stay within +/-5% of 20 seconds; elapsed={elapsed:?}"
    );
}

/// # Cancel safety
///
/// cancel-safe: all I/O is in-memory and every non-cancel-safe operation is allowed to
/// finish; the test does not race an exact read against a timeout.
#[tokio::test(flavor = "current_thread", start_paused = true)]
async fn shaped_stream_backpressure_preserves_payloads_larger_than_the_internal_queue() {
    let (left_wire, right_wire) = duplex(FRAME_SIZE * 4);
    let mut left = OpusVoip.wrap(left_wire);
    let mut right = OpusVoip.wrap(right_wire);
    let payload = vec![0x5a; 192 * 1024];
    let expected = payload.clone();

    let mut writers = JoinSet::new();
    writers.spawn(async move {
        left.write_all(&payload).await.unwrap();
        (payload.len(), left)
    });
    tokio::task::yield_now().await;
    assert!(writers.try_join_next().is_none(), "a stalled peer must propagate bounded backpressure");

    let mut received = vec![0_u8; expected.len()];
    right.read_exact(&mut received).await.unwrap();
    let (written, _left) = writers.join_next().await.unwrap().unwrap();
    assert_eq!(expected.len(), written);

    assert_eq!(received, expected);
}

/// # Cancel safety
///
/// cancel-safe: the malformed in-memory peer is closed before the single bounded read is
/// awaited, so the read always reaches a terminal result.
#[tokio::test(flavor = "current_thread")]
async fn shaped_stream_rejects_a_truncated_frame_header() {
    let (wire, mut malformed_peer) = duplex(FRAME_SIZE);
    let mut shaped = OpusVoip.wrap(wire);
    malformed_peer.write_all(&[0, FRAME_SIZE as u8]).await.unwrap();
    malformed_peer.shutdown().await.unwrap();

    let mut byte = [0_u8; 1];
    let error = shaped.read(&mut byte).await.expect_err("a partial frame header must not be treated as a clean EOF");

    assert_eq!(std::io::ErrorKind::UnexpectedEof, error.kind());
}

/// # Cancel safety
///
/// cancel-safe: the flush and bounded peer read are joined in one in-memory session, so
/// cancellation drops both endpoints together.
#[tokio::test(flavor = "current_thread", start_paused = true)]
async fn flush_waits_until_accepted_payload_reaches_the_peer_stream() {
    let (wire, mut peer) = duplex(FRAME_SIZE * 2);
    let mut shaped = OpusVoip.wrap(wire);
    let payload = b"flush acknowledgement";

    let sender = async move {
        shaped.write_all(payload).await.unwrap();
        shaped.flush().await.unwrap();
    };
    let receiver = async {
        let mut frame = [0_u8; FRAME_SIZE];
        peer.read_exact(&mut frame).await.unwrap();
        frame
    };
    let ((), frame) = tokio::join!(sender, receiver);

    assert_eq!(FRAME_SIZE, usize::from(u16::from_be_bytes([frame[0], frame[1]])));
    assert_eq!(payload.len(), usize::from(u16::from_be_bytes([frame[2], frame[3]])));
    assert_eq!(payload, &frame[4..4 + payload.len()]);
}

/// # Cancel safety
///
/// cancel-safe: both cooperative endpoints and every bounded read remain owned by this
/// in-memory test, so cancellation cannot leave external state behind.
#[tokio::test(flavor = "current_thread", start_paused = true)]
async fn write_half_close_keeps_the_reverse_read_path_alive() {
    let (left_wire, right_wire) = duplex(FRAME_SIZE * 4);
    let mut left = OpusVoip.wrap(left_wire);
    let mut right = OpusVoip.wrap(right_wire);
    let outbound = b"request before half close";
    let reverse = b"response after half close";

    let left_application = async {
        left.write_all(outbound).await.unwrap();
        left.shutdown().await.unwrap();
        let mut received = vec![0_u8; reverse.len()];
        left.read_exact(&mut received).await.unwrap();
        received
    };
    let right_application = async {
        let mut received = vec![0_u8; outbound.len()];
        right.read_exact(&mut received).await.unwrap();
        let mut eof_probe = [0_u8; 1];
        assert_eq!(0, right.read(&mut eof_probe).await.unwrap());
        right.write_all(reverse).await.unwrap();
        right.shutdown().await.unwrap();
        received
    };
    let (received_reverse, received_outbound) = tokio::join!(left_application, right_application);

    assert_eq!(reverse, received_reverse.as_slice());
    assert_eq!(outbound, received_outbound.as_slice());
}

/// # Cancel safety
///
/// cancel-safe: the only manual poll uses a zero-capacity buffer and must complete without
/// registering asynchronous work.
#[tokio::test(flavor = "current_thread")]
async fn zero_capacity_read_buffer_completes_immediately() {
    let (wire, _peer) = duplex(FRAME_SIZE);
    let mut shaped = OpusVoip.wrap(wire);
    let mut storage = [];
    let mut read_buffer = ReadBuf::new(&mut storage);
    let mut context = Context::from_waker(noop_waker_ref());

    let result = Pin::new(&mut shaped).poll_read(&mut context, &mut read_buffer);

    assert!(matches!(result, Poll::Ready(Ok(()))));
}

/// # Cancel safety
///
/// cancel-safe: each malformed peer is closed before its bounded read is awaited, so every
/// case reaches a terminal result without external state.
#[tokio::test(flavor = "multi_thread", worker_threads = 2)]
async fn shaped_stream_rejects_invalid_frame_lengths() {
    for header in [[0, 199, 0, 0], [0, 200, 0, 197]] {
        let (wire, mut malformed_peer) = duplex(FRAME_SIZE);
        let mut shaped = OpusVoip.wrap(wire);
        malformed_peer.write_all(&header).await.unwrap();
        malformed_peer.shutdown().await.unwrap();

        let mut byte = [0_u8; 1];
        let error = shaped.read(&mut byte).await.expect_err("invalid frame lengths must fail closed");

        assert_eq!(std::io::ErrorKind::InvalidData, error.kind());
    }
}

/// # Cancel safety
///
/// cancel-safe: the test owns the in-memory peers and reads a fixed four-frame profile cycle
/// before dropping them together.
#[tokio::test(flavor = "current_thread", start_paused = true)]
async fn webrtc_video_frames_stay_bounded_and_report_real_padding_overhead() {
    let profile = TrafficShapeProfile::WebRtcVideo;
    assert_eq!(600, profile.minimum_frame_bytes());
    assert_eq!(1_200, profile.maximum_frame_bytes());
    assert_eq!(Duration::from_millis(10), profile.tick_interval());

    let (left_wire, mut left_relay) = duplex(4_800);
    let (mut right_relay, right_wire) = duplex(4_800);
    let mut left = WebRtcVideo.wrap(left_wire);
    let mut right = WebRtcVideo.wrap(right_wire);
    let stats = right.stats();
    let payload = b"bounded video-shaped payload";

    let relay = async move {
        let mut observed_sizes = Vec::with_capacity(4);
        for _ in 0..4 {
            let mut header = [0_u8; 4];
            right_relay.read_exact(&mut header).await.unwrap();
            let frame_size = usize::from(u16::from_be_bytes([header[0], header[1]]));
            let mut body = vec![0_u8; frame_size - header.len()];
            right_relay.read_exact(&mut body).await.unwrap();
            left_relay.write_all(&header).await.unwrap();
            left_relay.write_all(&body).await.unwrap();
            observed_sizes.push(frame_size);
        }
        observed_sizes
    };
    let application = async {
        right.write_all(payload).await.unwrap();
        let mut received = vec![0_u8; payload.len()];
        left.read_exact(&mut received).await.unwrap();
        received
    };
    let (observed_sizes, received) = tokio::join!(relay, application);

    assert_eq!(payload, received.as_slice());
    assert_eq!(vec![600, 900, 1_200, 900], observed_sizes);
    let snapshot = stats.snapshot();
    assert_eq!(payload.len() as u64, snapshot.transmitted_real_bytes);
    assert_eq!(3_600_u64 - payload.len() as u64, snapshot.transmitted_padded_bytes);
    assert_eq!(3, snapshot.transmitted_dummy_frames);
}
