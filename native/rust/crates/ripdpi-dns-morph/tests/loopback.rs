//! Runs the client against a loopback UDP bridge that answers with `Responder`, the bridge side of the
//! wire contract, optionally rewriting each reply to model a hostile or broken bridge.

use std::io;
use std::net::{SocketAddr, UdpSocket};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::Duration;

use hickory_proto::op::{Message, MessageType, OpCode, Query};
use hickory_proto::rr::{Name, RecordType};
use ripdpi_dns_morph::{
    Bridge, CHUNK_LEN, ENVELOPE_OVERHEAD, FETCH_DEADLINE, FetchError, MAX_DATAGRAM_LEN, MAX_PAYLOAD_LEN, MAX_ZONE_LEN,
    Responder, Subscriber, fetch,
};

const ZONE: &str = "Morph.Example.Test";
const SEED: [u8; 32] = [7; 32];
const TOKEN: [u8; 32] = [11; 32];
const OTHER_TOKEN: [u8; 32] = [12; 32];
const SHORT: Duration = Duration::from_millis(300);

type Rewrite = Box<dyn Fn(usize, Vec<u8>) -> Vec<u8> + Send>;

struct LoopbackBridge {
    address: SocketAddr,
    public_key: [u8; 32],
    received: Arc<AtomicUsize>,
    replies: Arc<Mutex<Vec<u8>>>,
    stop: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
}

impl LoopbackBridge {
    fn start(zone: &str, subscribers: Vec<Subscriber>, rewrite: Rewrite) -> Self {
        let responder = Responder::new(zone, &SEED, subscribers).expect("responder");
        let public_key = responder.public_key();
        let socket = UdpSocket::bind("127.0.0.1:0").expect("bind bridge");
        socket.set_read_timeout(Some(Duration::from_millis(20))).expect("bridge timeout");
        let address = socket.local_addr().expect("bridge address");
        let received = Arc::new(AtomicUsize::new(0));
        let replies = Arc::new(Mutex::new(Vec::new()));
        let stop = Arc::new(AtomicBool::new(false));
        let thread = {
            let (received, replies, stop) = (Arc::clone(&received), Arc::clone(&replies), Arc::clone(&stop));
            std::thread::spawn(move || {
                let mut buffer = [0u8; 1500];
                while !stop.load(Ordering::Relaxed) {
                    let Ok((len, peer)) = socket.recv_from(&mut buffer) else { continue };
                    let count = received.fetch_add(1, Ordering::Relaxed);
                    if let Some(reply) = responder.respond(&buffer[..len]) {
                        let reply = rewrite(count, reply);
                        replies.lock().expect("replies").extend_from_slice(&reply);
                        socket.send_to(&reply, peer).expect("bridge reply");
                    }
                }
            })
        };
        Self { address, public_key, received, replies, stop, thread: Some(thread) }
    }

    fn serving(payload: Vec<u8>, rewrite: Rewrite) -> Self {
        Self::start(ZONE, vec![Subscriber { token: TOKEN, payload }], rewrite)
    }

    fn bridge(&self, token: [u8; 32]) -> Bridge {
        Bridge::new(self.address, &ZONE.to_lowercase(), self.public_key, token).expect("bridge")
    }
}

impl Drop for LoopbackBridge {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::Relaxed);
        if let Some(thread) = self.thread.take() {
            thread.join().expect("bridge thread");
        }
    }
}

fn unchanged() -> Rewrite {
    Box::new(|_, reply| reply)
}

fn payload(len: usize, seed: u8) -> Vec<u8> {
    (0..len).map(|index| (index * 31 % 251) as u8 ^ seed).collect()
}

fn no_protect(_: i32) -> io::Result<()> {
    Ok(())
}

#[test]
fn delivers_a_sealed_payload_across_chunks() {
    let expected = payload(CHUNK_LEN * 5 + 17, 0);
    let server = LoopbackBridge::serving(expected.clone(), unchanged());
    let mut protected = None;

    let fetched = fetch(&server.bridge(TOKEN), FETCH_DEADLINE, |fd| {
        protected = Some(fd);
        Ok(())
    })
    .expect("fetch");

    assert_eq!(fetched, expected);
    assert!(protected.is_some(), "the socket is protected before use");
    assert_eq!(server.received.load(Ordering::Relaxed), (expected.len() + ENVELOPE_OVERHEAD).div_ceil(CHUNK_LEN));
    let wire = server.replies.lock().expect("replies").clone();
    assert!(
        !wire.windows(16).any(|window| expected.windows(16).any(|plain| plain == window)),
        "no 16-byte run of the payload appears on the wire"
    );
}

#[test]
fn each_subscriber_receives_only_its_own_payload() {
    let (mine, theirs) = (payload(300, 0), payload(300, 0x5a));
    let server = LoopbackBridge::start(
        ZONE,
        vec![
            Subscriber { token: TOKEN, payload: mine.clone() },
            Subscriber { token: OTHER_TOKEN, payload: theirs.clone() },
        ],
        unchanged(),
    );

    assert_eq!(fetch(&server.bridge(TOKEN), FETCH_DEADLINE, no_protect).expect("mine"), mine);
    assert_eq!(fetch(&server.bridge(OTHER_TOKEN), FETCH_DEADLINE, no_protect).expect("theirs"), theirs);
}

#[test]
fn an_unknown_token_gets_no_answer() {
    let server = LoopbackBridge::serving(payload(100, 0), unchanged());

    let error = fetch(&server.bridge([99; 32]), SHORT, no_protect).expect_err("unauthorized");

    assert!(matches!(error, FetchError::Timeout), "{error:?}");
    assert!(server.replies.lock().expect("replies").is_empty(), "the bridge stays silent for an unknown token");
}

#[test]
fn rejects_a_tampered_chunk() {
    let server = LoopbackBridge::serving(
        payload(400, 0),
        Box::new(|count, mut reply| {
            if count == 1 {
                // The TXT data ends the datagram; flip one sealed byte of the second chunk.
                let last = reply.len() - 1;
                reply[last] ^= 0x01;
            }
            reply
        }),
    );

    let error = fetch(&server.bridge(TOKEN), FETCH_DEADLINE, no_protect).expect_err("tampered payload");

    assert!(matches!(error, FetchError::BadSignature), "{error:?}");
}

#[test]
fn rejects_a_payload_signed_by_another_key() {
    let server = LoopbackBridge::serving(payload(100, 0), unchanged());
    let bridge = Bridge::new(server.address, ZONE, [9; 32], TOKEN).expect("bridge");

    let error = fetch(&bridge, FETCH_DEADLINE, no_protect).expect_err("foreign key");

    assert!(matches!(error, FetchError::BadSignature), "{error:?}");
}

#[test]
fn rejects_an_oversized_datagram() {
    let server = LoopbackBridge::serving(
        payload(100, 0),
        Box::new(|_, mut reply| {
            reply.resize(MAX_DATAGRAM_LEN + 1, 0);
            reply
        }),
    );

    let error = fetch(&server.bridge(TOKEN), FETCH_DEADLINE, no_protect).expect_err("oversized datagram");

    assert!(matches!(error, FetchError::OversizedDatagram), "{error:?}");
}

#[test]
fn rejects_an_oversized_envelope_before_reading_it() {
    let server = LoopbackBridge::serving(
        payload(100, 0),
        Box::new(|_, mut reply| {
            // The first chunk carries CHUNK_LEN envelope bytes after the 6-byte chunk header.
            let declared = u32::try_from(MAX_PAYLOAD_LEN + ENVELOPE_OVERHEAD + 1).expect("length").to_be_bytes();
            let data_start = reply.len() - CHUNK_LEN - 6;
            reply[data_start..data_start + 4].copy_from_slice(&declared);
            reply
        }),
    );

    let error = fetch(&server.bridge(TOKEN), FETCH_DEADLINE, no_protect).expect_err("oversized envelope");

    assert!(matches!(error, FetchError::PayloadTooLarge), "{error:?}");
    assert_eq!(server.received.load(Ordering::Relaxed), 1);
}

#[test]
fn a_maximum_length_zone_still_fits_the_datagram_cap() {
    let zone = format!("{}.{}", "a".repeat(40), "b".repeat(MAX_ZONE_LEN - 41));
    assert_eq!(zone.len(), MAX_ZONE_LEN);
    let largest = Arc::new(AtomicUsize::new(0));
    let observed = Arc::clone(&largest);
    let server = LoopbackBridge::start(
        &zone,
        vec![Subscriber { token: TOKEN, payload: payload(1000, 0) }],
        Box::new(move |_, reply| {
            observed.fetch_max(reply.len(), Ordering::Relaxed);
            reply
        }),
    );
    let bridge = Bridge::new(server.address, &zone, server.public_key, TOKEN).expect("bridge");

    assert_eq!(fetch(&bridge, FETCH_DEADLINE, no_protect).expect("fetch"), payload(1000, 0));
    assert!(largest.load(Ordering::Relaxed) <= MAX_DATAGRAM_LEN, "{}", largest.load(Ordering::Relaxed));
    assert!(Bridge::new(server.address, &format!("c{zone}"), server.public_key, TOKEN).is_err());
}

#[test]
fn refuses_bridge_payloads_outside_the_limits() {
    let over = vec![Subscriber { token: TOKEN, payload: payload(MAX_PAYLOAD_LEN + 1, 0) }];
    assert!(matches!(Responder::new(ZONE, &SEED, over), Err(FetchError::PayloadTooLarge)));
    assert!(Responder::new(ZONE, &SEED, vec![Subscriber { token: TOKEN, payload: Vec::new() }]).is_err());
}

#[test]
fn a_protect_failure_sends_nothing() {
    let server = LoopbackBridge::serving(payload(100, 0), unchanged());

    let error = fetch(&server.bridge(TOKEN), FETCH_DEADLINE, |_| Err(io::Error::other("protect refused")))
        .expect_err("protect failure");

    assert!(matches!(error, FetchError::Protect(_)), "{error:?}");
    std::thread::sleep(Duration::from_millis(50));
    assert_eq!(server.received.load(Ordering::Relaxed), 0);
}

#[test]
fn times_out_when_the_bridge_is_silent() {
    let silent = UdpSocket::bind("127.0.0.1:0").expect("bind");
    let bridge = Bridge::new(silent.local_addr().expect("address"), ZONE, [1; 32], TOKEN).expect("bridge");

    let error = fetch(&bridge, Duration::from_millis(200), no_protect).expect_err("silent bridge");

    assert!(matches!(error, FetchError::Timeout), "{error:?}");
}

#[test]
fn leaves_ordinary_queries_to_the_forwarder() {
    let responder =
        Responder::new(ZONE, &SEED, vec![Subscriber { token: TOKEN, payload: payload(10, 0) }]).expect("responder");
    let mut query = Message::new(1, MessageType::Query, OpCode::Query);
    query.add_query(Query::new(Name::from_ascii("www.example.com.").expect("name"), RecordType::A));

    assert!(responder.respond(&query.to_vec().expect("query")).is_none());
}

#[test]
fn rejects_invalid_bridge_declarations() {
    let address: SocketAddr = "192.0.2.1:53".parse().expect("address");
    assert!(Bridge::new(address, "", [0; 32], TOKEN).is_err());
    assert!(Bridge::new(address, "bad_zone.test", [0; 32], TOKEN).is_err());
    assert!(Bridge::new("0.0.0.0:53".parse().expect("address"), ZONE, [0; 32], TOKEN).is_err());
    assert!(Bridge::new("192.0.2.1:0".parse().expect("address"), ZONE, [0; 32], TOKEN).is_err());
}
