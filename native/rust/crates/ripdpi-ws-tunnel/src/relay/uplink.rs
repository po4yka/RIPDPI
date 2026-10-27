use std::io::{self, Read};
use std::net::TcpStream;
use std::sync::Arc;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread::{self, JoinHandle};
use std::time::Duration;

use super::outbound_queue::OutboundSender;
use super::packet_split::PacketSplitter;

const CLIENT_READ_TIMEOUT: Duration = Duration::from_millis(250);

pub(super) fn spawn_uplink_thread(
    reader: TcpStream,
    outbound_tx: OutboundSender,
    shutdown: Arc<AtomicBool>,
    splitter: PacketSplitter,
) -> io::Result<JoinHandle<()>> {
    thread::Builder::new()
        .name("ripdpi-ws-up".into())
        .spawn(move || uplink_loop(reader, outbound_tx, &shutdown, splitter))
        .map_err(|e| io::Error::other(format!("spawn ws-up: {e}")))
}

fn uplink_loop(
    mut reader: TcpStream,
    outbound_tx: OutboundSender,
    shutdown: &AtomicBool,
    mut splitter: PacketSplitter,
) {
    // Short read timeout so we can check the shutdown flag while keeping the
    // TCP reader responsive to relay shutdown.
    let _ = reader.set_read_timeout(Some(CLIENT_READ_TIMEOUT));
    let mut buf = [0u8; 16_384];

    loop {
        if shutdown.load(Ordering::Acquire) {
            break;
        }
        match reader.read(&mut buf) {
            Ok(0) => break,
            Ok(n) => {
                let frames = splitter.push(&buf[..n]);
                if !frames.into_iter().all(|frame| outbound_tx.send_with_backpressure(frame, shutdown)) {
                    break;
                }
            }
            Err(ref e) if e.kind() == io::ErrorKind::WouldBlock || e.kind() == io::ErrorKind::TimedOut => {
                continue;
            }
            Err(_) => break,
        }
    }
    shutdown.store(true, Ordering::Release);
}
