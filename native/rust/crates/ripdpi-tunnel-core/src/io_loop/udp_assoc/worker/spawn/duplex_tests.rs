use std::collections::HashSet;
use std::io::{Read, Write};
use std::net::{SocketAddr, TcpListener, UdpSocket};
use std::sync::atomic::Ordering;
use std::sync::{Arc, Condvar, Mutex, MutexGuard, mpsc};
use std::thread::ThreadId;
use std::time::{Duration, Instant};

use tokio_util::sync::CancellationToken;

use crate::io_loop::udp_assoc::association_state::{OutboundDatagram, UDP_OUTBOUND_QUEUE_CAPACITY, now_millis};
use crate::io_loop::udp_assoc::event_handling::UdpEvent;
use crate::io_loop::udp_assoc::worker::spawn_udp_association;
use crate::session::udp::UdpMemoryBudget;
use crate::session::{Auth, TargetAddr};

const DEADLINE: Duration = Duration::from_secs(5);

#[derive(Default)]
struct SchedulerState {
    parked: HashSet<ThreadId>,
    paused: bool,
    paused_workers: usize,
}

#[derive(Default)]
struct SchedulerGate {
    state: Mutex<SchedulerState>,
    changed: Condvar,
}

struct PausedRuntime<'a>(&'a SchedulerGate);

impl SchedulerGate {
    fn parked(&self) {
        let mut state = self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        state.parked.insert(std::thread::current().id());
        self.changed.notify_all();
    }

    fn unparked(&self) {
        let mut state = self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        state.parked.remove(&std::thread::current().id());
        if state.paused {
            state.paused_workers += 1;
            self.changed.notify_all();
            state =
                self.changed.wait_while(state, |state| state.paused).unwrap_or_else(std::sync::PoisonError::into_inner);
            state.paused_workers -= 1;
        }
        self.changed.notify_all();
    }

    fn wait_for(&self, mut ready: impl FnMut(&SchedulerState) -> bool) -> MutexGuard<'_, SchedulerState> {
        let state = self.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        let (state, _) = self
            .changed
            .wait_timeout_while(state, DEADLINE, |state| !ready(state))
            .unwrap_or_else(std::sync::PoisonError::into_inner);
        assert!(ready(&state), "controlled runtime did not reach the requested scheduling boundary");
        state
    }

    fn pause_idle(&self) -> PausedRuntime<'_> {
        let mut state = self.wait_for(|state| state.parked.len() == 2);
        state.paused = true;
        PausedRuntime(self)
    }

    fn wait_for_paused_worker(&self) {
        drop(self.wait_for(|state| state.paused_workers != 0));
    }
}

impl Drop for PausedRuntime<'_> {
    fn drop(&mut self) {
        let mut state = self.0.state.lock().unwrap_or_else(std::sync::PoisonError::into_inner);
        state.paused = false;
        self.0.changed.notify_all();
    }
}

fn wait_until(mut ready: impl FnMut() -> bool) {
    let deadline = Instant::now() + DEADLINE;
    while !ready() {
        assert!(Instant::now() < deadline, "controlled peer did not reach the requested boundary");
        std::thread::yield_now();
    }
}

fn literal_frame(payload: &[u8]) -> Vec<u8> {
    let mut frame = vec![0, 0, 0, 1, 203, 0, 113, 9, 1, 187];
    frame.extend_from_slice(payload);
    frame
}

#[test]
fn ready_udp_reply_progresses_while_the_outbound_queue_remains_ready() {
    let listener = TcpListener::bind("127.0.0.1:0").expect("SOCKS listener");
    let proxy = listener.local_addr().expect("proxy address");
    let relay = Arc::new(UdpSocket::bind("127.0.0.1:0").expect("UDP relay"));
    relay.set_read_timeout(Some(DEADLINE)).expect("relay timeout");
    let peer_relay = Arc::clone(&relay);
    let (probe_tx, probe_rx) = mpsc::sync_channel(1);
    let peer = std::thread::spawn(move || -> std::io::Result<()> {
        let (mut control, _) = listener.accept()?;
        control.set_read_timeout(Some(DEADLINE))?;
        control.set_write_timeout(Some(DEADLINE))?;
        let mut greeting = [0; 3];
        control.read_exact(&mut greeting)?;
        assert_eq!(greeting, [5, 1, 0]);
        control.write_all(&[5, 0])?;
        let mut request = [0; 10];
        control.read_exact(&mut request)?;
        assert_eq!(request, [5, 3, 0, 1, 0, 0, 0, 0, 0, 0]);
        let port = peer_relay.local_addr()?.port().to_be_bytes();
        control.write_all(&[5, 0, 0, 1, 127, 0, 0, 1, port[0], port[1]])?;
        let mut buffer = [0; 64];
        let (length, source) = peer_relay.recv_from(&mut buffer)?;
        assert_eq!(&buffer[..length], literal_frame(b"probe"));
        probe_tx.send(source).expect("probe receipt");
        assert_eq!(control.read(&mut buffer)?, 0, "control closes during cleanup");
        Ok(())
    });

    let gate = Arc::new(SchedulerGate::default());
    let parked = Arc::clone(&gate);
    let unparked = Arc::clone(&gate);
    let runtime = tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .on_thread_park(move || parked.parked())
        .on_thread_unpark(move || unparked.unparked())
        .build()
        .expect("multithread runtime");
    let target: SocketAddr = "203.0.113.9:443".parse().expect("target");
    let source = "10.0.0.2:53000".parse().expect("source");
    let budget = UdpMemoryBudget::for_tunnel_mtu(1500);
    let (events, mut replies) = tokio::sync::mpsc::channel(1);
    let held_event_permit = runtime.block_on(events.clone().reserve_owned()).expect("reply backpressure");
    let association = {
        let _entered = runtime.enter();
        spawn_udp_association(
            proxy,
            Auth::NoAuth,
            source,
            target,
            1,
            Duration::from_secs(30),
            DEADLINE,
            None,
            budget.clone(),
            CancellationToken::new(),
            events,
        )
    };
    association
        .outbound
        .try_send(
            OutboundDatagram::try_new(TargetAddr::Ip(target), target, target, b"probe", &budget).expect("probe bytes"),
        )
        .expect("initial probe");
    let udp_source = probe_rx.recv_timeout(DEADLINE).expect("real SOCKS UDP request");
    let initial_pause = gate.pause_idle();
    // The scheduler lock orders the prior send before this reset; Relaxed publishes no UDP payload.
    wait_until(|| now_millis() != 0);
    association.last_activity.store(0, Ordering::Relaxed);
    relay.send_to(&literal_frame(b"first"), udp_source).expect("first incoming reply");
    relay.send_to(&literal_frame(b"second"), udp_source).expect("second incoming reply");
    drop(initial_pause);
    wait_until(|| association.last_activity.load(Ordering::Relaxed) != 0);

    // The first reply waits for the reserved event permit. Its successor is already on the socket.
    let paused = gate.pause_idle();
    for _ in 0..UDP_OUTBOUND_QUEUE_CAPACITY {
        association
            .outbound
            .try_send(
                OutboundDatagram::try_new(TargetAddr::Ip(target), target, target, b"queued", &budget)
                    .expect("queued bytes"),
            )
            .expect("fill production outbound queue");
    }
    drop(held_event_permit);
    gate.wait_for_paused_worker();
    drop(paused);
    let paused = gate.pause_idle();
    let pending_outbound = UDP_OUTBOUND_QUEUE_CAPACITY - association.outbound.capacity();
    drop(paused);
    let received = runtime.block_on(async {
        tokio::time::timeout(DEADLINE, async {
            let mut payloads = Vec::new();
            for _ in 0..2 {
                let UdpEvent::Packet { raw, .. } = replies.recv().await.expect("reply event") else {
                    panic!("duplex traffic must not close the association");
                };
                payloads.push(raw[28..].to_vec());
            }
            payloads
        })
        .await
    });
    association.cancel.cancel();
    runtime
        .block_on(async { tokio::time::timeout(DEADLINE, association.worker).await })
        .expect("worker cleanup deadline")
        .expect("worker cleanup");
    wait_until(|| peer.is_finished());
    peer.join().expect("peer panic").expect("peer I/O");
    runtime.shutdown_timeout(DEADLINE);
    assert_eq!(received.expect("reply deadline"), [b"first".to_vec(), b"second".to_vec()]);
    assert_eq!(
        pending_outbound,
        UDP_OUTBOUND_QUEUE_CAPACITY - 1,
        "a ready reply must yield to at most one outgoing datagram"
    );
}
