//! Runs the client against a loopback UDP bridge that answers with `Responder`, the bridge side of the
//! wire contract, optionally rewriting each reply to model a hostile or broken bridge.

use std::io;
use std::net::{SocketAddr, UdpSocket};
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::thread::JoinHandle;
use std::time::Duration;

use hickory_proto::op::{Message, MessageType, OpCode, Query};
use hickory_proto::rr::{Name, RecordType};
use ripdpi_dns_morph::{
    Bridge, CHUNK_LEN, FETCH_DEADLINE, FetchError, MAX_DATAGRAM_LEN, MAX_PAYLOAD_LEN, Responder, fetch,
};

const ZONE: &str = "Morph.Example.Test";
const SEED: [u8; 32] = [7; 32];

type Rewrite = Box<dyn Fn(usize, Vec<u8>) -> Vec<u8> + Send>;

struct LoopbackBridge {
    address: SocketAddr,
    public_key: [u8; 32],
    received: Arc<AtomicUsize>,
    stop: Arc<AtomicBool>,
    thread: Option<JoinHandle<()>>,
}

impl LoopbackBridge {
    fn start(payload: Vec<u8>, rewrite: Rewrite) -> Self {
        let responder = Responder::new(ZONE, &SEED, payload).expect("responder");
        let public_key = responder.public_key();
        let socket = UdpSocket::bind("127.0.0.1:0").expect("bind bridge");
        socket.set_read_timeout(Some(Duration::from_millis(20))).expect("bridge timeout");
        let address = socket.local_addr().expect("bridge address");
        let received = Arc::new(AtomicUsize::new(0));
        let stop = Arc::new(AtomicBool::new(false));
        let thread = {
            let (received, stop) = (Arc::clone(&received), Arc::clone(&stop));
            std::thread::spawn(move || {
                let mut buffer = [0u8; 1500];
                while !stop.load(Ordering::Relaxed) {
                    let Ok((len, peer)) = socket.recv_from(&mut buffer) else { continue };
                    let count = received.fetch_add(1, Ordering::Relaxed);
                    if let Some(reply) = responder.respond(&buffer[..len]) {
                        socket.send_to(&rewrite(count, reply), peer).expect("bridge reply");
                    }
                }
            })
        };
        Self { address, public_key, received, stop, thread: Some(thread) }
    }

    fn bridge(&self) -> Bridge {
        Bridge::new(self.address, &ZONE.to_lowercase(), self.public_key).expect("bridge")
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

fn payload(len: usize) -> Vec<u8> {
    (0..len).map(|index| (index * 31 % 251) as u8).collect()
}

fn no_protect(_: i32) -> io::Result<()> {
    Ok(())
}

#[test]
fn delivers_a_signed_payload_across_chunks() {
    let expected = payload(CHUNK_LEN * 5 + 17);
    let server = LoopbackBridge::start(expected.clone(), unchanged());
    let mut protected = None;

    let fetched = fetch(&server.bridge(), FETCH_DEADLINE, |fd| {
        protected = Some(fd);
        Ok(())
    })
    .expect("fetch");

    assert_eq!(fetched, expected);
    assert!(protected.is_some(), "the socket is protected before use");
    // Payload plus the 64-byte signature, one query per 180-byte chunk.
    assert_eq!(server.received.load(Ordering::Relaxed), (expected.len() + 64).div_ceil(CHUNK_LEN));
}

#[test]
fn rejects_a_tampered_chunk() {
    let server = LoopbackBridge::start(
        payload(400),
        Box::new(|count, mut reply| {
            if count == 1 {
                // The TXT data ends the datagram; flip one payload byte of the second chunk.
                let last = reply.len() - 1;
                reply[last] ^= 0x01;
            }
            reply
        }),
    );

    let error = fetch(&server.bridge(), FETCH_DEADLINE, no_protect).expect_err("tampered payload");

    assert!(matches!(error, FetchError::BadSignature), "{error:?}");
}

#[test]
fn rejects_a_payload_signed_by_another_key() {
    let server = LoopbackBridge::start(payload(100), unchanged());
    let bridge = Bridge::new(server.address, ZONE, [9; 32]).expect("bridge");

    let error = fetch(&bridge, FETCH_DEADLINE, no_protect).expect_err("foreign key");

    assert!(matches!(error, FetchError::BadSignature), "{error:?}");
}

#[test]
fn rejects_an_oversized_datagram() {
    let server = LoopbackBridge::start(
        payload(100),
        Box::new(|_, mut reply| {
            reply.resize(MAX_DATAGRAM_LEN + 1, 0);
            reply
        }),
    );

    let error = fetch(&server.bridge(), FETCH_DEADLINE, no_protect).expect_err("oversized datagram");

    assert!(matches!(error, FetchError::OversizedDatagram), "{error:?}");
}

#[test]
fn rejects_an_oversized_envelope_before_reading_it() {
    let server = LoopbackBridge::start(
        payload(100),
        Box::new(|_, reply| {
            // Rewrite the declared envelope length that opens the TXT data.
            let mut reply = reply;
            let declared = u32::try_from(MAX_PAYLOAD_LEN + 64 + 1).expect("length").to_be_bytes();
            let data_start = reply.len() - (100 + 64) - 6;
            reply[data_start..data_start + 4].copy_from_slice(&declared);
            reply
        }),
    );

    let error = fetch(&server.bridge(), FETCH_DEADLINE, no_protect).expect_err("oversized envelope");

    assert!(matches!(error, FetchError::PayloadTooLarge), "{error:?}");
    assert_eq!(server.received.load(Ordering::Relaxed), 1);
}

#[test]
fn a_maximum_length_zone_still_fits_the_datagram_cap() {
    let zone = format!("{}.{}", "a".repeat(63), "b".repeat(36));
    assert_eq!(zone.len(), 100);
    let responder = Responder::new(&zone, &SEED, payload(1000)).expect("responder");
    let socket = UdpSocket::bind("127.0.0.1:0").expect("bind bridge");
    let address = socket.local_addr().expect("address");
    let public_key = responder.public_key();
    let largest = Arc::new(AtomicUsize::new(0));
    let thread = {
        let largest = Arc::clone(&largest);
        std::thread::spawn(move || {
            let mut buffer = [0u8; 1500];
            for _ in 0..(1000 + 64usize).div_ceil(CHUNK_LEN) {
                let (len, peer) = socket.recv_from(&mut buffer).expect("query");
                let reply = responder.respond(&buffer[..len]).expect("reply");
                largest.fetch_max(reply.len(), Ordering::Relaxed);
                socket.send_to(&reply, peer).expect("reply");
            }
        })
    };

    let fetched = fetch(&Bridge::new(address, &zone, public_key).expect("bridge"), FETCH_DEADLINE, no_protect);

    thread.join().expect("bridge thread");
    assert_eq!(fetched.expect("fetch"), payload(1000));
    assert!(largest.load(Ordering::Relaxed) <= MAX_DATAGRAM_LEN);
    assert!(Bridge::new(address, &format!("c{zone}"), public_key).is_err(), "101 characters exceed the zone limit");
}

#[test]
fn refuses_a_bridge_payload_over_the_limit() {
    assert!(matches!(Responder::new(ZONE, &SEED, payload(MAX_PAYLOAD_LEN + 1)), Err(FetchError::PayloadTooLarge)));
    assert!(Responder::new(ZONE, &SEED, Vec::new()).is_err());
}

#[test]
fn a_protect_failure_sends_nothing() {
    let server = LoopbackBridge::start(payload(100), unchanged());

    let error = fetch(&server.bridge(), FETCH_DEADLINE, |_| Err(io::Error::other("protect refused")))
        .expect_err("protect failure");

    assert!(matches!(error, FetchError::Protect(_)), "{error:?}");
    std::thread::sleep(Duration::from_millis(50));
    assert_eq!(server.received.load(Ordering::Relaxed), 0);
}

#[test]
fn times_out_when_the_bridge_is_silent() {
    let silent = UdpSocket::bind("127.0.0.1:0").expect("bind");
    let bridge = Bridge::new(silent.local_addr().expect("address"), ZONE, [1; 32]).expect("bridge");

    let error = fetch(&bridge, Duration::from_millis(200), no_protect).expect_err("silent bridge");

    assert!(matches!(error, FetchError::Timeout), "{error:?}");
}

#[test]
fn leaves_ordinary_queries_to_the_forwarder() {
    let responder = Responder::new(ZONE, &SEED, payload(10)).expect("responder");
    let mut query = Message::new(1, MessageType::Query, OpCode::Query);
    query.add_query(Query::new(Name::from_ascii("www.example.com.").expect("name"), RecordType::A));

    assert!(responder.respond(&query.to_vec().expect("query")).is_none());
}

#[test]
fn rejects_invalid_bridge_declarations() {
    let address: SocketAddr = "192.0.2.1:53".parse().expect("address");
    assert!(Bridge::new(address, "", [0; 32]).is_err());
    assert!(Bridge::new(address, "bad_zone.test", [0; 32]).is_err());
    assert!(Bridge::new("0.0.0.0:53".parse().expect("address"), ZONE, [0; 32]).is_err());
    assert!(Bridge::new("192.0.2.1:0".parse().expect("address"), ZONE, [0; 32]).is_err());
}
