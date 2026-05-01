use std::io::{Read, Write};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, SyncSender, TryRecvError, TrySendError};
use std::thread;
use std::time::Duration;

use tungstenite::protocol::Message;
use tungstenite::WebSocket;

const OUTBOUND_QUEUE_CAPACITY: usize = 16;
const MAX_OUTBOUND_BURST: usize = 8;
const OUTBOUND_QUEUE_RETRY_DELAY: Duration = Duration::from_millis(1);

pub(super) type OutboundReceiver = Receiver<Vec<u8>>;

pub(super) struct OutboundSender {
    tx: SyncSender<Vec<u8>>,
}

pub(super) fn bounded_outbound_queue() -> (OutboundSender, OutboundReceiver) {
    let (tx, rx) = mpsc::sync_channel(OUTBOUND_QUEUE_CAPACITY);
    (OutboundSender { tx }, rx)
}

impl OutboundSender {
    pub(super) fn send_with_backpressure(&self, mut payload: Vec<u8>, shutdown: &AtomicBool) -> bool {
        loop {
            if shutdown.load(Ordering::Acquire) {
                return false;
            }
            match self.tx.try_send(payload) {
                Ok(()) => return true,
                Err(TrySendError::Full(returned_payload)) => {
                    payload = returned_payload;
                    thread::sleep(OUTBOUND_QUEUE_RETRY_DELAY);
                }
                Err(TrySendError::Disconnected(_)) => return false,
            }
        }
    }
}

pub(super) fn drain_outbound_frames<S: Read + Write>(
    ws: &mut WebSocket<S>,
    outbound_rx: &OutboundReceiver,
    shutdown: &AtomicBool,
) -> bool {
    let mut disconnected = false;
    for _ in 0..MAX_OUTBOUND_BURST {
        match outbound_rx.try_recv() {
            Ok(payload) => {
                if ws.send(Message::Binary(payload.into())).is_err() {
                    shutdown.store(true, Ordering::Release);
                    return true;
                }
            }
            Err(TryRecvError::Empty) => break,
            Err(TryRecvError::Disconnected) => {
                disconnected = true;
                break;
            }
        }
    }
    disconnected
}
