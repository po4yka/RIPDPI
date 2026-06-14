//! Mieru connection multiplexing (mux levels `low` / `middle` / `high`).
//!
//! Many logical sub-sessions — each its own `session_id` plus its own in-tunnel
//! SOCKS5 connect — share **one** carrier connection. Mieru's AEAD nonce is per
//! carrier *direction* (seeded once on the first segment, then advanced in
//! lockstep, per `PROTOCOL.md` §3), so multiplexing must not open a second AEAD
//! context per stream. Instead:
//!
//! - **One [`Encryptor`]** behind a [`tokio::sync::Mutex`]: every sub-session's
//!   segments are sealed through this single serialized writer, so the per-
//!   direction nonce is used exactly once regardless of how many streams write
//!   concurrently. This is the nonce-reuse-safety crux of the design.
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

use ring::rand::{SecureRandom, SystemRandom};
use ripdpi_network_time::NetworkTimeProvider;
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, DuplexStream};
use tokio::sync::{Mutex, Semaphore, mpsc};

use crate::config::{MieruConfig, MieruProtocol};
use crate::error::{MieruError, Result};
use crate::metadata::{DataAckMeta, ProtocolType, SessionMeta, session_id_of, timestamp_estimate_unix};
use crate::segment::{Decryptor, Encryptor, MAX_PDU, decapsulate, encapsulate};
use crate::session::{encode_socks5_address, split_host_port};

/// In-process duplex bridging the caller to a sub-session's pumps.
const BRIDGE_BUF: usize = 64 * 1024;
/// Pump copy buffer (one application chunk; `<= MAX_PDU`).
const PUMP_BUF: usize = 32 * 1024;
/// Per-sub-session inbound mailbox depth (decapsulated fragments).
const MAILBOX_DEPTH: usize = 64;

type DemuxTable = Arc<StdMutex<HashMap<u32, mpsc::Sender<Vec<u8>>>>>;

/// The single serialized outbound half of the carrier: one AEAD encryptor and
/// the carrier write half. Every sub-session funnels its segments through here
/// under a [`tokio::sync::Mutex`], guaranteeing the per-direction nonce is never
/// reused.
struct MuxWriter {
    enc: Encryptor,
    writer: Box<dyn AsyncWrite + Unpin + Send>,
    now_unix: i64,
}

impl MuxWriter {
    async fn send_open(&mut self, session_id: u32, seq: &mut u32) -> Result<()> {
        let (_, suffix) = self.enc.next_padding()?;
        let now = self.now_unix;
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
        let now = self.now_unix;
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

    async fn send_data(&mut self, session_id: u32, seq: &mut u32, bytes: &[u8]) -> Result<()> {
        for chunk in bytes.chunks(MAX_PDU) {
            let blob = encapsulate(chunk)?;
            let (prefix, suffix) = self.enc.next_padding()?;
            let now = self.now_unix;
            let this_seq = next(seq);
            let payload_len =
                u16::try_from(blob.len()).map_err(|_| MieruError::Protocol("fragment too large".to_owned()))?;
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
                    Some(&blob),
                )
                .await?;
        }
        Ok(())
    }
}

fn next(seq: &mut u32) -> u32 {
    let value = *seq;
    *seq = seq.wrapping_add(1);
    value
}

/// Inbound side of one sub-session: an mpsc mailbox of decapsulated fragments
/// fed by the carrier reader task, with a leftover buffer for partial reads.
struct MuxReader {
    rx: mpsc::Receiver<Vec<u8>>,
    leftover: Vec<u8>,
    pos: usize,
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
    writer: Arc<Mutex<MuxWriter>>,
    table: DemuxTable,
    limit: Arc<Semaphore>,
    rng: SystemRandom,
    /// The carrier reader task. Aborted on drop so the carrier read half is
    /// released (otherwise the reader would block forever on the half-closed
    /// carrier, leaking the connection).
    reader_task: tokio::task::JoinHandle<()>,
}

impl Drop for MieruMuxConnection {
    fn drop(&mut self) {
        self.reader_task.abort();
    }
}

impl MieruMuxConnection {
    /// Establish a multiplexed Mieru carrier over an already-connected, protected
    /// transport. One AEAD direction is shared by all sub-sessions; a background
    /// task reads and demultiplexes inbound segments. `time` is the shared
    /// network-time source (never the device clock); it is calibrated once from
    /// the server's first authenticated segment.
    pub async fn connect_over<T>(transport: T, config: &MieruConfig, time: Arc<NetworkTimeProvider>) -> Result<Self>
    where
        T: AsyncRead + AsyncWrite + Unpin + Send + 'static,
    {
        config.validate()?;
        if config.protocol == MieruProtocol::Udp {
            return Err(MieruError::UdpUnsupported);
        }
        let now_unix = time.now_unix();
        let key = crate::cipher::derive_key(config.password.as_bytes(), config.username.as_bytes(), now_unix, 0);
        let (read_half, write_half) = tokio::io::split(transport);
        let enc = Encryptor::new(key, config.username.as_bytes().to_vec());
        let dec = Decryptor::new(key);
        let table: DemuxTable = Arc::new(StdMutex::new(HashMap::new()));
        let writer = Arc::new(Mutex::new(MuxWriter { enc, writer: Box::new(write_half), now_unix }));
        let limit = Arc::new(Semaphore::new(config.multiplexing.max_concurrent_streams()));
        let reader_task = tokio::spawn(run_reader(dec, Box::new(read_half), Arc::clone(&table), time));
        Ok(Self { writer, table, limit, rng: SystemRandom::new(), reader_task })
    }

    /// Open a tunnelled byte stream to `target` (`host:port`) over a fresh
    /// sub-session multiplexed onto the shared carrier. Applies backpressure once
    /// the per-carrier concurrent-stream ceiling is reached.
    pub async fn open_stream(&self, target: &str) -> Result<DuplexStream> {
        let permit = Arc::clone(&self.limit)
            .acquire_owned()
            .await
            .map_err(|_| MieruError::Protocol("mux carrier closed".to_owned()))?;

        let (tx, rx) = mpsc::channel::<Vec<u8>>(MAILBOX_DEPTH);
        let session_id = self.register_session(tx)?;
        let mut reader = MuxReader::new(rx);
        let mut seq: u32 = 0;

        // Open-session handshake + in-tunnel SOCKS5 connect over this sub-session.
        let handshake = async {
            {
                let mut w = self.writer.lock().await;
                w.send_open(session_id, &mut seq).await?;
            }
            mux_socks5_connect(&self.writer, &mut reader, &mut seq, session_id, target).await
        }
        .await;
        if let Err(error) = handshake {
            self.deregister_session(session_id);
            return Err(error);
        }

        let (caller, engine) = tokio::io::duplex(BRIDGE_BUF);
        let (mut engine_read, mut engine_write) = tokio::io::split(engine);
        // The permit is released only once BOTH pumps finish (the stream is fully
        // closed), so the concurrent-stream ceiling reflects live streams.
        let permit = Arc::new(permit);

        // Outbound: caller writes -> data segments (serialized through the shared
        // writer so the nonce stays monotonic). Half-closing the write direction
        // sends a close-session but must NOT tear down the mailbox: inbound data
        // (e.g. an HTTP response to a request body that is already complete) may
        // still be arriving. The mailbox is removed by the inbound pump.
        let writer_out = Arc::clone(&self.writer);
        let permit_out = Arc::clone(&permit);
        tokio::spawn(async move {
            let mut buf = vec![0u8; PUMP_BUF];
            let mut seq = seq;
            loop {
                match engine_read.read(&mut buf).await {
                    Ok(0) | Err(_) => break,
                    Ok(n) => {
                        let mut w = writer_out.lock().await;
                        if w.send_data(session_id, &mut seq, &buf[..n]).await.is_err() {
                            break;
                        }
                    }
                }
            }
            {
                let mut w = writer_out.lock().await;
                let _ = w.send_close(session_id, &mut seq).await;
            }
            drop(permit_out);
        });

        // Inbound: this sub-session's mailbox -> caller reads. Ends when the
        // carrier closes the mailbox (server close / carrier death) or the caller
        // drops its read half; either way the sub-session is finished, so the
        // mailbox is deregistered here.
        let table_in = Arc::clone(&self.table);
        let permit_in = Arc::clone(&permit);
        tokio::spawn(async move {
            let mut buf = vec![0u8; PUMP_BUF];
            loop {
                let n = reader.read(&mut buf).await;
                if n == 0 || engine_write.write_all(&buf[..n]).await.is_err() {
                    break;
                }
            }
            deregister(&table_in, session_id);
            drop(permit_in);
        });

        Ok(caller)
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

    fn deregister_session(&self, session_id: u32) {
        deregister(&self.table, session_id);
    }
}

fn deregister(table: &DemuxTable, session_id: u32) {
    table.lock().unwrap_or_else(PoisonError::into_inner).remove(&session_id);
}

/// Single carrier reader: decrypt each inbound segment and route its payload to
/// the owning sub-session's mailbox by `session_id`. On carrier EOF/error, drop
/// every mailbox so all sub-sessions see EOF.
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
                let Some(blob) = payload else { continue };
                let Ok(data) = decapsulate(&blob) else { continue };
                if data.is_empty() {
                    continue;
                }
                let sender = { table.lock().unwrap_or_else(PoisonError::into_inner).get(&session_id).cloned() };
                if let Some(sender) = sender {
                    // Bounded mailbox: awaiting applies backpressure to this
                    // sub-session. The send only fails if the sub-session was
                    // already torn down, in which case the segment is dropped.
                    let _ = sender.send(data).await;
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
async fn mux_socks5_connect(
    writer: &Arc<Mutex<MuxWriter>>,
    reader: &mut MuxReader,
    seq: &mut u32,
    session_id: u32,
    target: &str,
) -> Result<()> {
    let (host, port) = split_host_port(target)?;

    send_data_locked(writer, session_id, seq, &[0x05, 0x01, 0x00]).await?;
    let mut greeting = [0u8; 2];
    reader.read_exact(&mut greeting).await?;
    if greeting != [0x05, 0x00] {
        return Err(MieruError::Socks5(format!("unexpected method selection {greeting:?}")));
    }

    let mut request = vec![0x05, 0x01, 0x00];
    encode_socks5_address(&mut request, host, port)?;
    send_data_locked(writer, session_id, seq, &request).await?;

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

async fn send_data_locked(writer: &Arc<Mutex<MuxWriter>>, session_id: u32, seq: &mut u32, bytes: &[u8]) -> Result<()> {
    let mut w = writer.lock().await;
    w.send_data(session_id, seq, bytes).await
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::cipher::{self, KEY_LEN};
    use crate::config::{MieruConfig, MieruMux, MieruProtocol};

    const NOW: i64 = 1_700_000_000;
    const USERNAME: &str = "alice";
    const PASSWORD: &str = "correct-horse-battery";

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
        Greeted,
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
                    let Some(blob) = payload else { continue };
                    let data = decapsulate(&blob)?;
                    let entry = state.entry(session_id).or_insert(SessionState::New);
                    match *entry {
                        SessionState::New => {
                            assert_eq!(data, vec![0x05, 0x01, 0x00], "SOCKS5 greeting for session {session_id}");
                            *entry = SessionState::Greeted;
                            server_send(&mut enc, &mut write_half, &mut seqs, session_id, &[0x05, 0x00]).await?;
                        }
                        SessionState::Greeted => {
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
            let blob = encapsulate(chunk)?;
            let (prefix, suffix) = enc.next_padding()?;
            let seq = seqs.entry(session_id).or_insert(0);
            let this_seq = next(seq);
            let payload_len = u16::try_from(blob.len()).expect("fragment fits u16");
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
                Some(&blob),
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
    async fn sequential_reuse_keeps_nonce_monotonic_across_many_streams() {
        // Many sequential streams over one carrier: the server decrypts every
        // segment with a single lockstep Decryptor, so a reused/desynced nonce
        // would surface as a decrypt error (Err from the server) and fail here.
        let key = cipher::derive_key(PASSWORD.as_bytes(), USERNAME.as_bytes(), NOW, 0);
        let (client_side, server_side) = tokio::io::duplex(1 << 16);
        let server = tokio::spawn(run_mux_server(server_side, key, USERNAME.as_bytes().to_vec()));

        let time = Arc::new(NetworkTimeProvider::fixed(NOW));
        let conn = MieruMuxConnection::connect_over(client_side, &config(MieruMux::Low), time).await.expect("carrier");

        for round in 0u8..6 {
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
}
