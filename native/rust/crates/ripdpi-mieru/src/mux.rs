//! Mieru connection multiplexing (mux levels `low` / `middle` / `high`).
//!
//! Many logical sub-sessions — each its own `session_id` plus its own in-tunnel
//! SOCKS5 connect — share **one** carrier connection. Mieru's AEAD nonce is per
//! carrier *direction* (seeded once on the first segment, then advanced in
//! lockstep, per `PROTOCOL.md` §3), so multiplexing must not open a second AEAD
//! context per stream. Instead:
//!
//! - **One [`Encryptor`]** owned by a dedicated writer task: every sub-session's segments are sealed through its bounded command queue, so the per-direction nonce is used exactly once and an abandoned caller future cannot cancel a partially written frame.
//! - **One [`Decryptor`]** in a single reader task that demultiplexes inbound
//!   segments to per-sub-session mailboxes keyed by `session_id`. A sub-session
//!   only ever reads its own mailbox, so streams cannot cross-contaminate.
//!
//! The concurrent-stream ceiling per carrier comes from
//! [`MieruMux::max_concurrent_streams`]; [`MieruMuxConnection::open_stream`]
//! applies backpressure (an async semaphore) once the ceiling is reached. See
//! `PROTOCOL.md` §7.

use std::collections::HashMap;
use std::sync::{Arc, Mutex as StdMutex, PoisonError};
use std::time::Duration;

use ring::rand::{SecureRandom, SystemRandom};
use ripdpi_network_time::NetworkTimeProvider;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};
use tokio::sync::{Semaphore, mpsc, oneshot};
use tokio::time::timeout;

use crate::MieruStream;
use crate::config::{MieruConfig, MieruProtocol};
use crate::error::{MieruError, Result};
use crate::metadata::{DataAckMeta, ProtocolType, SessionMeta, session_id_of, timestamp_estimate_unix};
use crate::owned_tasks::OwnedTasks;
use crate::segment::{Decryptor, Encryptor, MAX_PDU};
use crate::session::{encode_socks5_address, split_host_port};

/// In-process duplex bridging the caller to a sub-session's pumps.
const BRIDGE_BUF: usize = 64 * 1024;
/// Pump copy buffer (one application chunk; `<= MAX_PDU`).
const PUMP_BUF: usize = 32 * 1024;
/// Per-sub-session inbound mailbox depth (decrypted fragments).
const MAILBOX_DEPTH: usize = 64;
/// Bounded commands waiting for the single cancel-safe carrier writer.
const WRITER_QUEUE_DEPTH: usize = 256;
/// Upper bound on the open-session + in-tunnel SOCKS5 handshake. A wedged or
/// half-broken (write-ok, no-response) carrier must not hang `open_stream`
/// forever — especially since a multiplexed carrier is reused for every stream.
const HANDSHAKE_TIMEOUT: Duration = Duration::from_secs(10);

type DemuxTable = Arc<StdMutex<HashMap<u32, mpsc::Sender<Vec<u8>>>>>;

/// The single serialized outbound half of the carrier: one AEAD encryptor and
/// the carrier write half. Every sub-session funnels its segments through here
/// under a [`tokio::sync::Mutex`], guaranteeing the per-direction nonce is never
/// reused. `time` stamps each segment's metadata timestamp with the *current*
/// network time (not a value frozen at carrier open), so a long-lived carrier's
/// segments stay fresh against a server that enforces per-segment timestamps.
struct MuxWriter {
    enc: Encryptor,
    writer: Box<dyn AsyncWrite + Unpin + Send>,
    time: Arc<NetworkTimeProvider>,
}

impl MuxWriter {
    async fn send_open(&mut self, session_id: u32, seq: &mut u32) -> Result<()> {
        let (_, suffix) = self.enc.next_padding()?;
        let now = self.time.now_unix();
        let this_seq = next(seq);
        self.enc
            .write_segment(
                &mut self.writer,
                0,
                suffix,
                |_p, suffix_len| {
                    SessionMeta {
                        protocol: ProtocolType::OpenSessionRequest,
                        session_id,
                        seq: this_seq,
                        status_code: 0,
                        payload_len: 0,
                        suffix_len,
                    }
                    .marshal(now)
                },
                None,
            )
            .await
    }

    async fn send_close(&mut self, session_id: u32, seq: &mut u32) -> Result<()> {
        let (_, suffix) = self.enc.next_padding()?;
        let now = self.time.now_unix();
        let this_seq = next(seq);
        self.enc
            .write_segment(
                &mut self.writer,
                0,
                suffix,
                |_p, suffix_len| {
                    SessionMeta {
                        protocol: ProtocolType::CloseSessionRequest,
                        session_id,
                        seq: this_seq,
                        status_code: 0,
                        payload_len: 0,
                        suffix_len,
                    }
                    .marshal(now)
                },
                None,
            )
            .await
    }

    /// # Cancel safety:
    /// Not cancel-safe: partial writes advance AEAD and sequence state. The
    /// owning writer may be aborted only while discarding the whole carrier.
    async fn send_data(&mut self, session_id: u32, seq: &mut u32, bytes: &[u8]) -> Result<()> {
        for chunk in bytes.chunks(MAX_PDU) {
            let (prefix, suffix) = self.enc.next_padding()?;
            let now = self.time.now_unix();
            let this_seq = next(seq);
            let payload_len =
                u16::try_from(chunk.len()).map_err(|_| MieruError::Protocol("fragment too large".to_owned()))?;
            self.enc
                .write_segment(
                    &mut self.writer,
                    prefix,
                    suffix,
                    |prefix_len, suffix_len| {
                        DataAckMeta {
                            protocol: ProtocolType::DataClientToServer,
                            session_id,
                            seq: this_seq,
                            unack_seq: 0,
                            window_size: 0,
                            fragment: 0,
                            prefix_len,
                            payload_len,
                            suffix_len,
                        }
                        .marshal(now)
                    },
                    Some(chunk),
                )
                .await?;
        }
        Ok(())
    }
}

#[derive(Clone)]
struct MuxWriteQueue {
    tx: mpsc::Sender<WriterCommand>,
    carrier: Arc<OwnedTasks>,
    table: DemuxTable,
    limit: Arc<Semaphore>,
}

enum WriterCommandKind {
    Open,
    Data(Vec<u8>),
    Close,
}

struct WriterCommand {
    session_id: u32,
    kind: WriterCommandKind,
    done: oneshot::Sender<Result<()>>,
}

impl MuxWriteQueue {
    async fn send_open(&self, session_id: u32) -> Result<()> {
        self.send(session_id, WriterCommandKind::Open).await
    }

    async fn send_data(&self, session_id: u32, bytes: &[u8]) -> Result<()> {
        self.send(session_id, WriterCommandKind::Data(bytes.to_vec())).await
    }

    async fn send(&self, session_id: u32, kind: WriterCommandKind) -> Result<()> {
        let (done, completed) = oneshot::channel();
        self.tx
            .send(WriterCommand { session_id, kind, done })
            .await
            .map_err(|_| MieruError::Protocol("mux carrier writer closed".to_owned()))?;
        completed.await.map_err(|_| MieruError::Protocol("mux carrier writer closed".to_owned()))?
    }

    fn try_close(&self, session_id: u32) {
        let (done, _completed) = oneshot::channel();
        match self.tx.try_send(WriterCommand { session_id, kind: WriterCommandKind::Close, done }) {
            Ok(()) | Err(mpsc::error::TrySendError::Closed(_)) => {}
            Err(mpsc::error::TrySendError::Full(_)) => {
                // A lost Close would orphan the session remotely. Discard the
                // carrier without spawning unbounded teardown work; retain joins.
                self.limit.close();
                self.carrier.abort();
                self.table.lock().unwrap_or_else(PoisonError::into_inner).clear();
            }
        }
    }
}

async fn run_writer(mut writer: MuxWriter, mut commands: mpsc::Receiver<WriterCommand>, table: DemuxTable) {
    let mut seqs = HashMap::<u32, u32>::new();
    while let Some(command) = commands.recv().await {
        let seq = seqs.entry(command.session_id).or_insert(0);
        let closes = matches!(&command.kind, WriterCommandKind::Close);
        let result = match command.kind {
            WriterCommandKind::Open => writer.send_open(command.session_id, seq).await,
            WriterCommandKind::Data(bytes) => writer.send_data(command.session_id, seq, &bytes).await,
            WriterCommandKind::Close => writer.send_close(command.session_id, seq).await,
        };
        if closes {
            seqs.remove(&command.session_id);
        }
        let failed = result.is_err();
        let _ = command.done.send(result);
        if failed {
            break;
        }
    }
    table.lock().unwrap_or_else(PoisonError::into_inner).clear();
}

fn next(seq: &mut u32) -> u32 {
    let value = *seq;
    *seq = seq.wrapping_add(1);
    value
}

/// Inbound side of one sub-session: an mpsc mailbox of decrypted fragments
/// fed by the carrier reader task, with a leftover buffer for partial reads.
struct MuxReader {
    rx: mpsc::Receiver<Vec<u8>>,
    leftover: Vec<u8>,
    pos: usize,
}

pub(crate) struct SessionRegistration {
    table: DemuxTable,
    writer: MuxWriteQueue,
    session_id: u32,
    _permit: tokio::sync::OwnedSemaphorePermit,
}

impl SessionRegistration {
    fn new(
        table: DemuxTable,
        writer: MuxWriteQueue,
        session_id: u32,
        permit: tokio::sync::OwnedSemaphorePermit,
    ) -> Self {
        Self { table, writer, session_id, _permit: permit }
    }
}

impl Drop for SessionRegistration {
    fn drop(&mut self) {
        deregister(&self.table, self.session_id);
        self.writer.try_close(self.session_id);
    }
}

impl MuxReader {
    fn new(rx: mpsc::Receiver<Vec<u8>>) -> Self {
        Self { rx, leftover: Vec::new(), pos: 0 }
    }

    /// Read up to `out.len()` bytes; returns bytes read (0 = the carrier closed
    /// this sub-session's mailbox).
    async fn read(&mut self, out: &mut [u8]) -> usize {
        while self.pos >= self.leftover.len() {
            match self.rx.recv().await {
                Some(chunk) => {
                    self.leftover = chunk;
                    self.pos = 0;
                }
                None => return 0,
            }
        }
        let available = &self.leftover[self.pos..];
        let n = available.len().min(out.len());
        out[..n].copy_from_slice(&available[..n]);
        self.pos += n;
        n
    }

    async fn read_exact(&mut self, out: &mut [u8]) -> Result<()> {
        let mut filled = 0;
        while filled < out.len() {
            let n = self.read(&mut out[filled..]).await;
            if n == 0 {
                return Err(MieruError::Socks5("unexpected EOF during SOCKS5 negotiation".to_owned()));
            }
            filled += n;
        }
        Ok(())
    }
}

/// A multiplexed Mieru carrier: reusable across many [`open_stream`] calls.
///
/// [`open_stream`]: MieruMuxConnection::open_stream
pub struct MieruMuxConnection {
    writer: MuxWriteQueue,
    table: DemuxTable,
    limit: Arc<Semaphore>,
    rng: SystemRandom,
    carrier: Arc<OwnedTasks>,
    streams: StdMutex<StreamTasks>,
}

#[derive(Default)]
struct StreamTasks {
    closed: bool,
    groups: Vec<Arc<OwnedTasks>>,
}

impl Drop for MieruMuxConnection {
    fn drop(&mut self) {
        self.cancel();
    }
}

impl MieruMuxConnection {
    /// Establish a multiplexed Mieru carrier over an already-connected, protected
    /// transport. One AEAD direction is shared by all sub-sessions; a background
    /// task reads and demultiplexes inbound segments. `time` is the shared
    /// network-time source (never the device clock); it is calibrated once from
    /// the server's first authenticated segment.
    /// # Cancel safety:
    /// Cancel-safe: there is no await; both spawned tasks belong to the returned
    /// carrier before this future can yield.
    pub async fn connect_over<T>(transport: T, config: &MieruConfig, time: Arc<NetworkTimeProvider>) -> Result<Self>
    where
        T: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        config.validate()?;
        if config.protocol == MieruProtocol::Udp {
            return Err(MieruError::UdpUnsupported);
        }
        let key = crate::cipher::derive_key(config.password.as_bytes(), config.username.as_bytes(), time.now_unix(), 0);
        let (read_half, write_half) = tokio::io::split(transport);
        let enc = Encryptor::new(key, config.username.as_bytes().to_vec());
        let dec = Decryptor::new(key);
        let table: DemuxTable = Arc::new(StdMutex::new(HashMap::new()));
        let (writer_tx, writer_rx) = mpsc::channel(WRITER_QUEUE_DEPTH);
        let writer_task = run_writer(
            MuxWriter { enc, writer: Box::new(write_half), time: Arc::clone(&time) },
            writer_rx,
            Arc::clone(&table),
        );
        let limit = Arc::new(Semaphore::new(config.multiplexing.max_concurrent_streams()));
        let reader_task = run_reader(dec, Box::new(read_half), Arc::clone(&table), time);
        // Carrier tasks do not retain the queue, so this shared owner has no cycle.
        let carrier = Arc::new(OwnedTasks::spawn(reader_task, writer_task));
        let writer = MuxWriteQueue {
            tx: writer_tx,
            carrier: Arc::clone(&carrier),
            table: Arc::clone(&table),
            limit: Arc::clone(&limit),
        };
        Ok(Self {
            writer,
            table,
            limit,
            rng: SystemRandom::new(),
            carrier,
            streams: StdMutex::new(StreamTasks::default()),
        })
    }

    /// Open a tunnelled byte stream to `target` (`host:port`) over a fresh
    /// sub-session multiplexed onto the shared carrier. Applies backpressure once
    /// the per-carrier concurrent-stream ceiling is reached.
    /// # Cancel safety:
    /// Cancel-safe: the registration guard removes the route and queues Close,
    /// or aborts an overloaded carrier. Committed commands remain writer-owned;
    /// pump registration and stream publication have no intervening await.
    pub async fn open_stream(&self, target: &str) -> Result<MieruStream> {
        let permit = Arc::clone(&self.limit)
            .acquire_owned()
            .await
            .map_err(|_| MieruError::Protocol("mux carrier closed".to_owned()))?;

        let (tx, rx) = mpsc::channel::<Vec<u8>>(MAILBOX_DEPTH);
        let session_id = self.register_session(tx)?;
        let registration = SessionRegistration::new(Arc::clone(&self.table), self.writer.clone(), session_id, permit);
        let mut reader = MuxReader::new(rx);

        // Open-session handshake + in-tunnel SOCKS5 connect, time-bounded so a
        // wedged or half-broken carrier cannot hang the caller indefinitely.
        let handshake = match timeout(HANDSHAKE_TIMEOUT, async {
            self.writer.send_open(session_id).await?;
            mux_socks5_connect(&self.writer, &mut reader, session_id, target).await
        })
        .await
        {
            Ok(result) => result,
            Err(_elapsed) => Err(MieruError::Protocol("Mieru sub-session handshake timed out".to_owned())),
        };
        if let Err(error) = handshake {
            // Dropping the guard queues Close or aborts an overloaded carrier,
            // without extending the handshake deadline on a stalled writer.
            drop(registration);
            return Err(error);
        }

        let (caller, engine) = tokio::io::duplex(BRIDGE_BUF);
        let (mut engine_read, mut engine_write) = tokio::io::split(engine);

        // The logical stream owns its registration and permit. A write
        // half-close must not tear down the read direction or release its slot.
        let writer_out = self.writer.clone();
        let outbound = async move {
            let mut buf = vec![0u8; PUMP_BUF];
            loop {
                match engine_read.read(&mut buf).await {
                    Ok(0) | Err(_) => break,
                    Ok(n) => {
                        if writer_out.send_data(session_id, &buf[..n]).await.is_err() {
                            break;
                        }
                    }
                }
            }
        };

        // Inbound: this sub-session's mailbox -> caller reads. Ends when the
        // carrier closes the mailbox (server close, carrier death, or `Drop`
        // clearing the table) or a caller-side write error; deregisters on exit.
        let table_in = Arc::clone(&self.table);
        let inbound = async move {
            let mut buf = vec![0u8; PUMP_BUF];
            loop {
                let n = reader.read(&mut buf).await;
                if n == 0 || engine_write.write_all(&buf[..n]).await.is_err() {
                    break;
                }
            }
            deregister(&table_in, session_id);
        };

        let mut streams = self.streams.lock().unwrap_or_else(PoisonError::into_inner);
        if streams.closed || self.limit.is_closed() {
            return Err(MieruError::Protocol("mux carrier closed".into()));
        }
        // Finished groups are joined before being removed; the parent never
        // loses ownership merely because a child stream was dropped.
        let mut index = 0;
        while index < streams.groups.len() {
            if streams.groups[index].reap_finished()? {
                streams.groups.swap_remove(index);
            } else {
                index += 1;
            }
        }
        let tasks = Arc::new(OwnedTasks::spawn(inbound, outbound));
        streams.groups.push(Arc::clone(&tasks));
        Ok(MieruStream::new(caller, tasks, Some(registration)))
    }

    /// Stop admission and abort every owned task before any shutdown await.
    /// Signal every owned task to stop. Call `close` to join before reporting stopped.
    pub fn cancel(&self) {
        self.limit.close();
        let mut streams = self.streams.lock().unwrap_or_else(PoisonError::into_inner);
        streams.closed = true;
        for group in &streams.groups {
            group.abort();
        }
        self.carrier.abort();
        self.table.lock().unwrap_or_else(PoisonError::into_inner).clear();
    }

    /// # Cancel safety
    /// Cancel-safe: groups stay registered until all joins finish. Cancellation
    /// leaves the connection closed, with handles available for another close.
    pub async fn close(&self) -> Result<()> {
        self.cancel();
        let groups = self.streams.lock().unwrap_or_else(PoisonError::into_inner).groups.clone();
        let mut failure = None;
        for group in &groups {
            if let Err(error) = group.close().await {
                failure.get_or_insert(error);
            }
        }
        if let Err(error) = self.carrier.close().await {
            failure.get_or_insert(error);
        }
        if let Some(error) = failure {
            return Err(error);
        }
        self.streams.lock().unwrap_or_else(PoisonError::into_inner).groups.clear();
        Ok(())
    }

    /// Allocate a unique non-zero `session_id` and register its mailbox in one
    /// locked step (so two concurrent opens cannot collide on an id).
    fn register_session(&self, tx: mpsc::Sender<Vec<u8>>) -> Result<u32> {
        let mut table = self.table.lock().unwrap_or_else(PoisonError::into_inner);
        let session_id = loop {
            let mut bytes = [0u8; 4];
            self.rng.fill(&mut bytes).map_err(|_| MieruError::Crypto("session id rng"))?;
            let id = u32::from_be_bytes(bytes);
            let id = if id == 0 { 1 } else { id };
            if !table.contains_key(&id) {
                break id;
            }
        };
        table.insert(session_id, tx);
        Ok(session_id)
    }
}

fn deregister(table: &DemuxTable, session_id: u32) {
    table.lock().unwrap_or_else(PoisonError::into_inner).remove(&session_id);
}

/// Single carrier reader: decrypt each inbound segment and route its payload to
/// the owning sub-session's mailbox by `session_id`. On carrier EOF/error, drop
/// every mailbox so all sub-sessions see EOF.
/// # Cancel safety:
/// Conditional: a partial frame cannot be resumed after cancellation. The owner
/// must discard the carrier and clear the demux table, as cancel/close do.
async fn run_reader(
    mut dec: Decryptor,
    mut reader: Box<dyn AsyncRead + Unpin + Send>,
    table: DemuxTable,
    time: Arc<NetworkTimeProvider>,
) {
    let mut calibrated = false;
    loop {
        let Ok((meta, payload)) = dec.read_segment(&mut reader).await else { break };
        // Calibrate the shared replay clock once from the server's first
        // (AEAD-verified) timestamp; minute granularity makes per-segment
        // re-anchoring jitter backwards, so do it a single time.
        if !calibrated {
            time.calibrate(timestamp_estimate_unix(&meta));
            calibrated = true;
        }
        let session_id = session_id_of(&meta);
        let Ok(protocol) = ProtocolType::from_u8(meta[0]) else { continue };
        match protocol {
            ProtocolType::DataServerToClient | ProtocolType::DataClientToServer => {
                let Some(data) = payload else { continue };
                if data.is_empty() {
                    continue;
                }
                let sender = { table.lock().unwrap_or_else(PoisonError::into_inner).get(&session_id).cloned() };
                if let Some(sender) = sender {
                    // The carrier reader must never await one stream's bounded mailbox: doing so head-of-line blocks every other multiplexed stream. A full mailbox isolates the slow stream by closing its local receive path; carrier demux continues immediately.
                    if sender.try_send(data).is_err() {
                        deregister(&table, session_id);
                    }
                }
            }
            ProtocolType::CloseSessionRequest | ProtocolType::CloseSessionResponse | ProtocolType::CloseConnRequest => {
                deregister(&table, session_id);
            }
            // open/close responses and acks: transparently skip.
            _ => {}
        }
    }
    table.lock().unwrap_or_else(PoisonError::into_inner).clear();
}

/// In-tunnel SOCKS5 client handshake + CONNECT over a multiplexed sub-session,
/// writing through the shared serialized writer and reading from the sub-
/// session's mailbox.
/// # Cancel safety:
/// Conditional: queued commands can finish and partial replies can be consumed.
/// The caller's registration guard must remove and close the logical session.
async fn mux_socks5_connect(
    writer: &MuxWriteQueue,
    reader: &mut MuxReader,
    session_id: u32,
    target: &str,
) -> Result<()> {
    let (host, port) = split_host_port(target)?;

    // Authentication belongs to the Mieru carrier. Upstream expects CONNECT
    // directly, without another SOCKS method-negotiation exchange.
    let mut request = vec![0x05, 0x01, 0x00];
    encode_socks5_address(&mut request, host, port)?;
    writer.send_data(session_id, &request).await?;

    let mut head = [0u8; 4];
    reader.read_exact(&mut head).await?;
    if head[0] != 0x05 {
        return Err(MieruError::Socks5(format!("bad SOCKS5 reply version {}", head[0])));
    }
    if head[1] != 0x00 {
        return Err(MieruError::Socks5(format!("SOCKS5 CONNECT failed, reply code {}", head[1])));
    }
    let addr_len = match head[3] {
        0x01 => 4,
        0x04 => 16,
        0x03 => {
            let mut len = [0u8; 1];
            reader.read_exact(&mut len).await?;
            usize::from(len[0])
        }
        other => return Err(MieruError::Socks5(format!("unknown SOCKS5 reply atyp {other}"))),
    };
    let mut rest = vec![0u8; addr_len + 2];
    reader.read_exact(&mut rest).await?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::pin::Pin;
    use std::task::{Context, Poll, Waker};
    use tokio::io::DuplexStream;

    use crate::cipher::{self, KEY_LEN};
    use crate::config::{MieruConfig, MieruMux, MieruProtocol};
    use tokio::io::ReadBuf;
    use tokio::sync::Notify;

    const NOW: i64 = 1_700_000_000;
    const USERNAME: &str = "alice";
    const PASSWORD: &str = "correct-horse-battery";

    /// # Cancel safety:
    /// Not cancel-safe: the test joins the carrier after observing peer EOF.
    #[tokio::test]
    async fn cancelled_opens_close_carrier_when_teardown_queue_is_full() {
        let (client, mut peer) = tokio::io::duplex(8);
        let conn =
            MieruMuxConnection::connect_over(client, &config(MieruMux::Low), Arc::new(NetworkTimeProvider::fixed(NOW)))
                .await
                .expect("carrier");
        for _ in 0..=WRITER_QUEUE_DEPTH {
            let mut open = Box::pin(conn.open_stream("backpressure.example:443"));
            std::future::poll_fn(|cx| {
                assert!(!matches!(open.as_mut().poll(cx), Poll::Ready(Ok(_))));
                Poll::Ready(())
            })
            .await;
            drop(open);
            tokio::task::yield_now().await;
            if conn.limit.is_closed() {
                break;
            }
        }
        let admission_closed = conn.limit.is_closed();
        let mut bytes = Vec::new();
        let stopped = timeout(Duration::from_millis(200), peer.read_to_end(&mut bytes)).await;
        conn.close().await.expect("join retained carrier tasks");
        assert!(admission_closed, "lost teardown must close carrier admission");
        stopped.expect("peer EOF without dropping or explicitly closing connection").expect("peer read");
    }

    #[derive(Default)]
    struct BlockingWriteState {
        bytes: Vec<u8>,
        released: bool,
        waker: Option<Waker>,
        flushes: usize,
    }

    struct BlockingTransport {
        state: Arc<StdMutex<BlockingWriteState>>,
        blocked: Arc<Notify>,
    }

    impl AsyncRead for BlockingTransport {
        fn poll_read(self: Pin<&mut Self>, _cx: &mut Context<'_>, _buf: &mut ReadBuf<'_>) -> Poll<std::io::Result<()>> {
            Poll::Pending
        }
    }

    impl AsyncWrite for BlockingTransport {
        fn poll_write(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<std::io::Result<usize>> {
            let mut state = self.state.lock().expect("blocking writer state");
            if state.bytes.len() >= 8 && !state.released {
                state.waker = Some(cx.waker().clone());
                self.blocked.notify_one();
                return Poll::Pending;
            }
            let count = buf.len().min(4);
            state.bytes.extend_from_slice(&buf[..count]);
            Poll::Ready(Ok(count))
        }

        fn poll_flush(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
            self.state.lock().expect("blocking writer state").flushes += 1;
            Poll::Ready(Ok(()))
        }

        fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<std::io::Result<()>> {
            Poll::Ready(Ok(()))
        }
    }

    fn config(mux: MieruMux) -> MieruConfig {
        MieruConfig {
            server: "loopback".to_owned(),
            port: 443,
            username: USERNAME.to_owned(),
            password: PASSWORD.to_owned(),
            protocol: MieruProtocol::Tcp,
            multiplexing: mux,
            mtu: 1400,
        }
    }

    #[derive(PartialEq)]
    enum SessionState {
        New,
        Connected,
    }

    /// Spec-faithful multiplexing server: one AEAD direction each way, demuxes by
    /// session_id, runs the in-tunnel SOCKS5 per session, then echoes data back
    /// tagged with the same session_id. A single read/write loop keeps the
    /// server's own nonce in lockstep — so if the client ever reused a nonce the
    /// server's decrypt would fail and the test would error.
    async fn run_mux_server(transport: DuplexStream, key: [u8; KEY_LEN], username: Vec<u8>) -> Result<()> {
        let (mut read_half, mut write_half) = tokio::io::split(transport);
        let mut dec = Decryptor::new(key);
        let mut enc = Encryptor::new(key, username);
        let mut state: HashMap<u32, SessionState> = HashMap::new();
        let mut seqs: HashMap<u32, u32> = HashMap::new();

        loop {
            let (meta, payload) = match dec.read_segment(&mut read_half).await {
                Ok(segment) => segment,
                Err(MieruError::Io(e)) if e.kind() == std::io::ErrorKind::UnexpectedEof => return Ok(()),
                Err(e) => return Err(e),
            };
            let session_id = session_id_of(&meta);
            let protocol = ProtocolType::from_u8(meta[0])?;
            match protocol {
                ProtocolType::OpenSessionRequest => {
                    state.entry(session_id).or_insert(SessionState::New);
                }
                ProtocolType::CloseSessionRequest => {
                    state.remove(&session_id);
                }
                ProtocolType::DataClientToServer => {
                    let Some(data) = payload else { continue };
                    let entry = state.entry(session_id).or_insert(SessionState::New);
                    match *entry {
                        SessionState::New => {
                            assert_eq!(data[0], 0x05);
                            assert_eq!(data[1], 0x01, "CONNECT command for session {session_id}");
                            *entry = SessionState::Connected;
                            let reply = [0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0];
                            server_send(&mut enc, &mut write_half, &mut seqs, session_id, &reply).await?;
                        }
                        SessionState::Connected => {
                            server_send(&mut enc, &mut write_half, &mut seqs, session_id, &data).await?;
                        }
                    }
                }
                _ => {}
            }
        }
    }

    async fn server_send<W>(
        enc: &mut Encryptor,
        writer: &mut W,
        seqs: &mut HashMap<u32, u32>,
        session_id: u32,
        bytes: &[u8],
    ) -> Result<()>
    where
        W: AsyncWrite + Unpin,
    {
        for chunk in bytes.chunks(MAX_PDU) {
            let (prefix, suffix) = enc.next_padding()?;
            let seq = seqs.entry(session_id).or_insert(0);
            let this_seq = next(seq);
            let payload_len = u16::try_from(chunk.len()).expect("fragment fits u16");
            enc.write_segment(
                writer,
                prefix,
                suffix,
                |prefix_len, suffix_len| {
                    DataAckMeta {
                        protocol: ProtocolType::DataServerToClient,
                        session_id,
                        seq: this_seq,
                        unack_seq: 0,
                        window_size: 0,
                        fragment: 0,
                        prefix_len,
                        payload_len,
                        suffix_len,
                    }
                    .marshal(NOW)
                },
                Some(chunk),
            )
            .await?;
        }
        Ok(())
    }

    #[tokio::test]
    async fn concurrent_streams_round_trip_without_cross_contamination() {
        let key = cipher::derive_key(PASSWORD.as_bytes(), USERNAME.as_bytes(), NOW, 0);
        let (client_side, server_side) = tokio::io::duplex(1 << 16);
        let server = tokio::spawn(run_mux_server(server_side, key, USERNAME.as_bytes().to_vec()));

        let time = Arc::new(NetworkTimeProvider::fixed(NOW));
        let conn = Arc::new(
            MieruMuxConnection::connect_over(client_side, &config(MieruMux::Middle), time).await.expect("carrier open"),
        );

        // Three concurrent sub-sessions, each with a distinct repeated byte.
        let mut handles = Vec::new();
        for (index, marker) in [0xAAu8, 0xBB, 0xCC].into_iter().enumerate() {
            let conn = Arc::clone(&conn);
            handles.push(tokio::spawn(async move {
                let target = format!("host{index}.example:443");
                let stream = conn.open_stream(&target).await.expect("open sub-stream");
                let (mut read_half, mut write_half) = tokio::io::split(stream);
                let payload = vec![marker; 48 * 1024];
                let expected = payload.clone();
                let write = async move {
                    write_half.write_all(&payload).await.expect("write");
                    write_half.shutdown().await.expect("shutdown");
                };
                let read = async move {
                    let mut got = vec![0u8; expected.len()];
                    read_half.read_exact(&mut got).await.expect("read echo");
                    // Isolation: every byte echoed back must be THIS stream's marker.
                    assert!(got.iter().all(|&b| b == marker), "stream {index} received foreign bytes");
                    assert_eq!(got, expected, "stream {index} round-trip mismatch");
                };
                tokio::join!(write, read);
            }));
        }
        for handle in handles {
            handle.await.expect("sub-stream task");
        }

        drop(conn);
        let _ = server.await;
    }

    #[tokio::test]
    async fn saturated_stream_mailbox_does_not_block_other_streams() {
        let key = cipher::derive_key(PASSWORD.as_bytes(), USERNAME.as_bytes(), NOW, 0);
        let (reader_side, mut writer_side) = tokio::io::duplex(8 << 20);
        let table: DemuxTable = Arc::new(StdMutex::new(HashMap::new()));
        let (slow_tx, _slow_rx) = mpsc::channel(MAILBOX_DEPTH);
        let (fast_tx, mut fast_rx) = mpsc::channel(MAILBOX_DEPTH);
        let slow_id = 11;
        let fast_id = 22;
        {
            let mut guard = table.lock().expect("demux table");
            guard.insert(slow_id, slow_tx);
            guard.insert(fast_id, fast_tx);
        }
        let reader = tokio::spawn(run_reader(
            Decryptor::new(key),
            Box::new(reader_side),
            Arc::clone(&table),
            Arc::new(NetworkTimeProvider::fixed(NOW)),
        ));
        let mut enc = Encryptor::new(key, USERNAME.as_bytes().to_vec());
        let mut seqs = HashMap::new();

        for _ in 0..=MAILBOX_DEPTH {
            server_send(&mut enc, &mut writer_side, &mut seqs, slow_id, b"slow").await.expect("slow segment");
        }
        server_send(&mut enc, &mut writer_side, &mut seqs, fast_id, b"fast").await.expect("fast segment");

        let fast = timeout(Duration::from_millis(100), fast_rx.recv())
            .await
            .expect("a saturated stream must not stall carrier demux")
            .expect("fast mailbox remains open");
        assert_eq!(fast, b"fast");
        assert!(!table.lock().expect("demux table").contains_key(&slow_id), "saturated stream must be isolated");

        drop(writer_side);
        reader.await.expect("reader task");
    }

    #[tokio::test]
    async fn sequential_reuse_keeps_nonce_monotonic_across_many_streams() {
        // Many sequential streams over one carrier: the server decrypts every
        // segment with a single lockstep Decryptor, so a reused/desynced nonce
        // would surface as a decrypt error (Err from the server) and fail here.
        //
        // 12 rounds exceeds the Low ceiling of 8 concurrent streams: each stream's
        // permit must be released when its outbound side closes, otherwise the 9th
        // open would block on the semaphore forever (this is the regression test
        // for the half-idle permit leak — the test server never sends a close
        // response, so the permit cannot depend on the inbound pump ending).
        let key = cipher::derive_key(PASSWORD.as_bytes(), USERNAME.as_bytes(), NOW, 0);
        let (client_side, server_side) = tokio::io::duplex(1 << 16);
        let server = tokio::spawn(run_mux_server(server_side, key, USERNAME.as_bytes().to_vec()));

        let time = Arc::new(NetworkTimeProvider::fixed(NOW));
        let conn = MieruMuxConnection::connect_over(client_side, &config(MieruMux::Low), time).await.expect("carrier");

        for round in 0u8..12 {
            let stream = conn.open_stream("reuse.example:80").await.expect("open");
            let (mut read_half, mut write_half) = tokio::io::split(stream);
            let payload = vec![round; 4096];
            let expected = payload.clone();
            write_half.write_all(&payload).await.expect("write");
            write_half.shutdown().await.expect("shutdown");
            let mut got = vec![0u8; expected.len()];
            read_half.read_exact(&mut got).await.expect("echo");
            assert_eq!(got, expected, "round {round} echo mismatch");
        }

        // Dropping the connection aborts the reader task, releasing the carrier
        // read half so the server observes EOF and shuts down cleanly.
        drop(conn);
        let _ = server.await;
    }

    /// # Cancel safety
    /// Not cancel-safe: the complete test joins its owned peer after the observation.
    #[tokio::test]
    async fn dropping_idle_stream_releases_mailbox_and_permit() {
        let key = cipher::derive_key(PASSWORD.as_bytes(), USERNAME.as_bytes(), NOW, 0);
        let (client_side, server_side) = tokio::io::duplex(1 << 16);
        let mut server = tokio::spawn(run_mux_server(server_side, key, USERNAME.as_bytes().to_vec()));
        let conn = MieruMuxConnection::connect_over(
            client_side,
            &config(MieruMux::Low),
            Arc::new(NetworkTimeProvider::fixed(NOW)),
        )
        .await
        .expect("carrier");
        let stream = conn.open_stream("idle.example:443").await.expect("open");
        drop(stream);
        let released = timeout(Duration::from_millis(200), async {
            loop {
                if conn.table.lock().expect("table").is_empty()
                    && conn.limit.available_permits() == MieruMux::Low.max_concurrent_streams()
                {
                    break;
                }
                tokio::task::yield_now().await;
            }
        })
        .await;
        drop(conn);
        let stopped = timeout(Duration::from_secs(1), &mut server).await;
        if stopped.is_err() {
            server.abort();
            let _ = server.await;
        }
        stopped.expect("peer EOF after connection drop").expect("peer task").expect("peer result");
        released.expect("idle stream drop must release mailbox and concurrency permit");
    }

    /// # Cancel safety
    /// Not cancel-safe: the test explicitly joins the active writer and peer.
    #[tokio::test]
    async fn close_joins_active_pumps_and_carrier_before_return() {
        let key = cipher::derive_key(PASSWORD.as_bytes(), USERNAME.as_bytes(), NOW, 0);
        let (client_side, server_side) = tokio::io::duplex(1 << 16);
        let mut server = tokio::spawn(run_mux_server(server_side, key, USERNAME.as_bytes().to_vec()));
        let conn = MieruMuxConnection::connect_over(
            client_side,
            &config(MieruMux::Low),
            Arc::new(NetworkTimeProvider::fixed(NOW)),
        )
        .await
        .expect("carrier");
        let mut stream = conn.open_stream("active.example:443").await.expect("open");
        let (started, ready) = oneshot::channel();
        let mut writing = tokio::spawn(async move {
            stream.write_all(&[0x5a; 65536]).await.expect("initial buffered payload");
            let _ = started.send(());
            loop {
                if stream.write_all(&[0xa5; 32768]).await.is_err() {
                    break;
                }
            }
        });
        ready.await.expect("writer active");
        timeout(Duration::from_secs(1), conn.close()).await.expect("close deadline").expect("close");
        assert!(conn.carrier.reap_finished().expect("carrier joined"));
        assert!(conn.streams.lock().expect("groups").groups.is_empty());
        assert!(conn.table.lock().expect("table").is_empty());
        let written = timeout(Duration::from_secs(1), &mut writing).await;
        if written.is_err() {
            writing.abort();
            let _ = writing.await;
        }
        let stopped = timeout(Duration::from_secs(1), &mut server).await;
        if stopped.is_err() {
            server.abort();
            let _ = server.await;
        }
        written.expect("application writer observes close").expect("writer task");
        stopped.expect("peer observes EOF").expect("peer task").expect("peer result");
        conn.close().await.expect("repeat close");
    }

    #[tokio::test(start_paused = true)]
    async fn open_stream_times_out_on_a_silent_carrier() {
        // A carrier whose peer reads but never replies: the in-tunnel SOCKS5
        // handshake can never complete, so open_stream must time out instead of
        // hanging forever. Paused time auto-advances to fire the timeout instantly.
        let (client_side, server_side) = tokio::io::duplex(1 << 16);
        let server = tokio::spawn(async move {
            let (mut read_half, _write_half) = tokio::io::split(server_side);
            let mut buf = [0u8; 4096];
            while read_half.read(&mut buf).await.is_ok_and(|n| n > 0) {}
        });

        let time = Arc::new(NetworkTimeProvider::fixed(NOW));
        let conn =
            MieruMuxConnection::connect_over(client_side, &config(MieruMux::Low), time).await.expect("carrier open");
        let result = conn.open_stream("nowhere.example:443").await;
        assert!(
            matches!(result, Err(MieruError::Protocol(_))),
            "a silent carrier must make open_stream time out, got {result:?}"
        );

        drop(conn);
        let _ = server.await;
    }

    #[tokio::test]
    async fn cancelling_open_stream_rolls_back_demux_registration() {
        let (client_side, server_side) = tokio::io::duplex(1 << 16);
        let server = tokio::spawn(async move {
            let (mut read_half, _write_half) = tokio::io::split(server_side);
            let mut buf = [0u8; 4096];
            while read_half.read(&mut buf).await.is_ok_and(|n| n > 0) {}
        });
        let conn = Arc::new(
            MieruMuxConnection::connect_over(
                client_side,
                &config(MieruMux::Low),
                Arc::new(NetworkTimeProvider::fixed(NOW)),
            )
            .await
            .expect("carrier open"),
        );
        let opening = {
            let conn = Arc::clone(&conn);
            tokio::spawn(async move { conn.open_stream("cancelled.example:443").await })
        };
        timeout(Duration::from_secs(1), async {
            while conn.table.lock().expect("demux table").is_empty() {
                tokio::task::yield_now().await;
            }
        })
        .await
        .expect("open registration");

        opening.abort();
        let _ = opening.await;
        assert!(conn.table.lock().expect("demux table").is_empty(), "cancelled open must deregister its mailbox");

        drop(conn);
        let _ = server.await;
    }

    #[tokio::test]
    async fn cancelling_frame_waiter_does_not_cancel_partial_carrier_write() {
        let key = cipher::derive_key(PASSWORD.as_bytes(), USERNAME.as_bytes(), NOW, 0);
        let state = Arc::new(StdMutex::new(BlockingWriteState::default()));
        let blocked = Arc::new(Notify::new());
        let transport = BlockingTransport { state: Arc::clone(&state), blocked: Arc::clone(&blocked) };
        let table: DemuxTable = Arc::new(StdMutex::new(HashMap::new()));
        let (tx, rx) = mpsc::channel(WRITER_QUEUE_DEPTH);
        let writer = run_writer(
            MuxWriter {
                enc: Encryptor::new(key, USERNAME.as_bytes().to_vec()),
                writer: Box::new(transport),
                time: Arc::new(NetworkTimeProvider::fixed(NOW)),
            },
            rx,
            Arc::clone(&table),
        );
        let carrier = Arc::new(OwnedTasks::spawn(std::future::pending(), writer));
        let handle = MuxWriteQueue { tx, carrier: Arc::clone(&carrier), table, limit: Arc::new(Semaphore::new(1)) };
        let first = {
            let handle = handle.clone();
            tokio::spawn(async move { handle.send_open(1).await })
        };
        blocked.notified().await;
        first.abort();
        let _ = first.await;
        if let Some(waker) = {
            let mut state = state.lock().expect("blocking writer state");
            state.released = true;
            state.waker.take()
        } {
            waker.wake();
        }

        timeout(Duration::from_secs(1), handle.send_open(2))
            .await
            .expect("writer must finish the abandoned frame and accept the next one")
            .expect("second frame write");
        assert_eq!(state.lock().expect("blocking writer state").flushes, 2);

        drop(handle);
        carrier.close().await.expect("writer task");
    }
}
