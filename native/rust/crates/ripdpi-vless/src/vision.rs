//! XTLS Vision flow-control framing for VLESS (`flow=xtls-rprx-vision`).
//!
//! Wraps the proxied application stream — the user's inner TLS-in-TLS — in
//! Vision padding chunks that mirror xray-core's `XtlsPadding`/`XtlsUnpadding`
//! (`proxy/proxy.go`), so a standard Xray VLESS+Reality server configured with
//! `flow=xtls-rprx-vision` interoperates.
//!
//! ## Wire format of one padding chunk (integers big-endian)
//!
//! ```text
//! [16-byte UUID]   only on the FIRST chunk written/read on a direction
//! command (1)      0x00 Continue | 0x01 End | 0x02 Direct
//! contentLen (u16)
//! paddingLen (u16)
//! content (contentLen bytes)
//! padding (paddingLen zero bytes)
//! ```
//!
//! The receiver reconstructs the byte stream purely from the `content` fields,
//! so chunk boundaries need not align with TLS records. Padding can stop after
//! a complete inner Application-Data (`0x17`) record, when the write ends at a
//! record boundary: that chunk carries `Direct` (the xtls splice signal), and
//! the stream is raw afterwards in that direction. The directions transition
//! independently.
//!
//! RIPDPI writes the VLESS request header eagerly before this wrapper engages,
//! so the xray "hide-header" zero-content chunk is not emitted. The UUID still
//! prefixes the first real chunk. For inner TLS 1.3 (seen in the downlink
//! ServerHello, as xray's `XtlsFilterTls` does), `Direct` switches reads and
//! writes to the transport beneath the outer Reality TLS stream, matching
//! xray's raw-conn splice; `End` keeps the outer layer. Non-TLS XUDP carriers
//! disable that splice so datagrams remain protected by Reality TLS. The
//! owner-only live test exercises a complete HTTPS exchange against a
//! Vision-enforcing Xray server.

use std::io;
use std::pin::Pin;
use std::task::{Context, Poll};

use ring::rand::{SecureRandom, SystemRandom};
use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

/// Raw transport access used after an XTLS `Direct` downlink command removes
/// the outer Reality TLS layer.
pub trait XtlsDirectRead: AsyncRead + Unpin {
    /// Polls the transport below the outer Reality TLS stream.
    fn poll_read_direct(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>>;
}

/// Raw transport access used after an XTLS `Direct` uplink command removes
/// the outer Reality TLS layer.
pub trait XtlsDirectWrite: AsyncWrite + Unpin {
    /// Writes to the transport below the outer Reality TLS stream.
    fn poll_write_direct(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>>;

    /// Flushes the transport below the outer Reality TLS stream.
    fn poll_flush_direct(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>>;

    /// Shuts down the transport below the outer Reality TLS stream.
    fn poll_shutdown_direct(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>>;
}

/// Vision command bytes (xray-core `proxy/proxy.go` const block).
const CMD_CONTINUE: u8 = 0x00;
/// Terminal command that stops padding without removing the TLS layer —
/// the transition xray uses when the connection is not splice-eligible.
const CMD_END: u8 = 0x01;
const CMD_DIRECT: u8 = 0x02;

/// TLS record content types used to find the handshake -> application-data
/// transition that ends padding.
const TLS_CHANGE_CIPHER_SPEC: u8 = 0x14;
const TLS_HANDSHAKE: u8 = 0x16;
const TLS_APPLICATION_DATA: u8 = 0x17;
/// Major version byte of every TLS 1.0-1.3 record header.
const TLS_RECORD_MAJOR_VERSION: u8 = 0x03;
const TLS_RECORD_HEADER_LEN: usize = 5;

/// Maximum content bytes per chunk — xray's `buf.Size - 21` (8192 minus the
/// 16-byte UUID and the 5-byte command block). Larger writes are split into
/// multiple chunks; in practice a relay write is <= 8 KiB so this rarely trips.
const MAX_CHUNK_CONTENT: usize = 8192 - 21;

/// Stop padding a non-TLS stream after this many chunks without a ClientHello,
/// mirroring xray's bounded TLS-detection window. A real TLS handshake flags
/// itself on its first record, so this only trips for non-TLS traffic (which
/// still works — it is sent unpadded inside the outer Reality TLS).
const NON_TLS_CHUNK_LIMIT: u32 = 4;

/// Scratch size when pulling padded bytes from the inner stream.
const READ_SCRATCH: usize = 8192;

/// XTLS Vision flow-control wrapper around a VLESS-over-Reality byte stream.
///
/// A single `VisionStream` is driven for both directions by the relay's
/// `copy_bidirectional`, so it holds independent uplink (write) and downlink
/// (read) state. With `flow=none` it is a transparent passthrough.
pub struct VisionStream<S> {
    inner: S,
    /// `false` for `flow=none`: transparent passthrough in both directions.
    vision: bool,
    /// Whether Vision may bypass the outer Reality TLS transport.
    allow_direct: bool,
    /// 16-byte user UUID, prefixed on the first chunk of each direction.
    uuid: [u8; 16],
    rng: SystemRandom,

    // ---- uplink (write) ----
    w_padding: bool,
    w_direct: bool,
    w_uuid_sent: bool,
    w_seen_handshake: bool,
    /// Set once the uplink shows a header that no TLS record can have.
    w_not_tls: bool,
    w_chunks: u32,
    w_rec_remaining: usize,
    w_hdr: [u8; TLS_RECORD_HEADER_LEN],
    w_hdr_len: usize,
    /// Encoded-but-not-yet-flushed bytes (only used during the padded prefix).
    w_pending: Vec<u8>,

    // ---- downlink (read) ----
    r_uuid_checked: bool,
    r_padding: bool,
    r_direct: bool,
    r_cur_cmd: u8,
    r_rem_cmd: i32,
    r_rem_content: i32,
    r_rem_padding: i32,
    /// Padded bytes read from the inner stream, awaiting unpadding.
    r_inbuf: Vec<u8>,
    /// De-padded payload ready to hand to the caller.
    r_out: Vec<u8>,
    /// Downlink bytes buffered until the inner ServerHello record is whole.
    r_hello: Vec<u8>,
    /// xray `EnableXtls`: `Some(true)` once the inner ServerHello negotiated
    /// TLS 1.3 with a cipher other than CCM_8. Only then may the uplink send
    /// `Direct`; otherwise (TLS 1.2, 0-RTT before the answer) it sends `End`.
    xtls_enabled: Option<bool>,
}

impl<S> VisionStream<S> {
    /// Wrap `inner` with real XTLS Vision framing (`flow=xtls-rprx-vision`).
    /// `uuid` is the binary 16-byte VLESS user id (the same bytes used in the
    /// VLESS request header).
    pub fn new_vision(inner: S, uuid: [u8; 16]) -> Self {
        Self::with_mode(inner, uuid, true, true)
    }

    /// Wrap a non-TLS payload stream with Vision framing while keeping every
    /// byte inside the outer Reality TLS transport.
    pub fn new_vision_tls_only(inner: S, uuid: [u8; 16]) -> Self {
        Self::with_mode(inner, uuid, true, false)
    }

    /// Wrap `inner` as a transparent passthrough (`flow=none`).
    pub fn new_passthrough(inner: S) -> Self {
        Self::with_mode(inner, [0u8; 16], false, false)
    }

    fn with_mode(inner: S, uuid: [u8; 16], vision: bool, allow_direct: bool) -> Self {
        Self {
            inner,
            vision,
            allow_direct,
            uuid,
            rng: SystemRandom::new(),
            w_padding: vision,
            w_direct: false,
            w_uuid_sent: false,
            w_seen_handshake: false,
            w_not_tls: false,
            w_chunks: 0,
            w_rec_remaining: 0,
            w_hdr: [0u8; TLS_RECORD_HEADER_LEN],
            w_hdr_len: 0,
            w_pending: Vec::new(),
            r_uuid_checked: false,
            r_padding: vision,
            r_direct: false,
            r_cur_cmd: CMD_CONTINUE,
            r_rem_cmd: -1,
            r_rem_content: -1,
            r_rem_padding: -1,
            r_inbuf: Vec::new(),
            r_out: Vec::new(),
            r_hello: Vec::new(),
            xtls_enabled: None,
        }
    }

    /// Pick a padding length for `content_len` bytes of content, following
    /// xray's `longPadding` heuristic. The exact value is interop-free (the
    /// receiver reads `paddingLen` from the header), so a CSPRNG is used purely
    /// for traffic-shape obfuscation.
    fn pick_padding(&self, content_len: usize) -> u16 {
        let mut bytes = [0u8; 2];
        // A fill failure (never on supported platforms) degrades to a
        // deterministic length rather than failing the connection.
        let _ = self.rng.fill(&mut bytes);
        let r = usize::from(u16::from_be_bytes(bytes));
        let raw = if content_len < 900 { r % 500 + 900 - content_len } else { r % 256 };
        let cap = MAX_CHUNK_CONTENT.saturating_sub(content_len);
        u16::try_from(raw.min(cap)).unwrap_or(u16::MAX)
    }

    /// Append one padding chunk for `content` with `cmd` to `w_pending`,
    /// prefixing the UUID exactly once per stream.
    fn push_chunk(&mut self, content: &[u8], cmd: u8) {
        let uuid = if self.w_uuid_sent {
            None
        } else {
            self.w_uuid_sent = true;
            Some(self.uuid)
        };
        let padding = self.pick_padding(content.len());
        encode_padding_chunk(&mut self.w_pending, uuid.as_ref(), cmd, content, padding);
    }

    /// Encode `buf` into one or more padding chunks. All but the last carry
    /// `Continue`; the last carries `final_cmd`.
    fn encode_outgoing(&mut self, buf: &[u8], final_cmd: u8) {
        if buf.is_empty() {
            self.push_chunk(&[], final_cmd);
            return;
        }
        let mut chunks = buf.chunks(MAX_CHUNK_CONTENT).peekable();
        while let Some(piece) = chunks.next() {
            let cmd = if chunks.peek().is_none() { final_cmd } else { CMD_CONTINUE };
            self.push_chunk(piece, cmd);
        }
    }

    /// Walk the outgoing TLS records in `buf`, tracking record boundaries across
    /// writes. Returns `true` after an inner application-data record completes
    /// (after at least one handshake record), only if no partial record remains
    /// at the end of this buffer.
    fn note_records(&mut self, buf: &[u8]) -> bool {
        if self.w_not_tls {
            return false;
        }
        let mut pos = 0;
        let mut completed_appdata = false;
        while pos < buf.len() {
            if self.w_rec_remaining > 0 {
                let skip = self.w_rec_remaining.min(buf.len() - pos);
                self.w_rec_remaining -= skip;
                pos += skip;
                if self.w_rec_remaining == 0 && self.w_hdr[0] == TLS_APPLICATION_DATA && self.w_seen_handshake {
                    completed_appdata = true;
                }
                continue;
            }
            while self.w_hdr_len < TLS_RECORD_HEADER_LEN && pos < buf.len() {
                self.w_hdr[self.w_hdr_len] = buf[pos];
                self.w_hdr_len += 1;
                pos += 1;
            }
            if self.w_hdr_len < TLS_RECORD_HEADER_LEN {
                break;
            }
            let record_type = self.w_hdr[0];
            // Only real TLS framing may arm the splice: a stray 0x16/0x17 byte in
            // plaintext must not send the rest of the stream outside Reality TLS.
            if self.w_hdr[1] != TLS_RECORD_MAJOR_VERSION
                || !(TLS_CHANGE_CIPHER_SPEC..=TLS_APPLICATION_DATA).contains(&record_type)
            {
                self.w_not_tls = true;
                self.w_seen_handshake = false;
                return false;
            }
            let record_len = usize::from(u16::from_be_bytes([self.w_hdr[3], self.w_hdr[4]]));
            if record_type == TLS_HANDSHAKE {
                self.w_seen_handshake = true;
            }
            self.w_rec_remaining = record_len;
            self.w_hdr_len = 0;
            if record_len == 0 && record_type == TLS_APPLICATION_DATA && self.w_seen_handshake {
                completed_appdata = true;
            }
        }
        completed_appdata && self.w_rec_remaining == 0 && self.w_hdr_len == 0
    }

    /// Run the XtlsUnpadding state machine over `r_inbuf`, moving de-padded
    /// content into `r_out`. Sets `r_direct` once an End/Direct command is seen
    /// (padding stops; the remainder is raw). Tolerant of chunk headers and
    /// content that span multiple reads.
    fn unpad(&mut self) {
        if !self.r_uuid_checked {
            if self.r_inbuf.len() < self.uuid.len() {
                return;
            }
            if self.r_inbuf[..16] == self.uuid[..] {
                self.r_uuid_checked = true;
                self.r_inbuf.drain(..16);
                self.r_rem_cmd = 5;
                self.r_rem_content = -1;
                self.r_rem_padding = -1;
            } else {
                // Not Vision-padded: deliver verbatim through the existing
                // outer stream and stop parsing.
                self.r_padding = false;
                self.r_out.append(&mut self.r_inbuf);
                return;
            }
        }

        let mut i = 0usize;
        let n = self.r_inbuf.len();
        while i < n {
            if self.r_rem_cmd > 0 {
                let b = self.r_inbuf[i];
                i += 1;
                match self.r_rem_cmd {
                    5 => self.r_cur_cmd = b,
                    4 => self.r_rem_content = i32::from(b) << 8,
                    3 => self.r_rem_content |= i32::from(b),
                    2 => self.r_rem_padding = i32::from(b) << 8,
                    1 => self.r_rem_padding |= i32::from(b),
                    _ => {}
                }
                self.r_rem_cmd -= 1;
            } else if self.r_rem_content > 0 {
                let take = (n - i).min(self.r_rem_content as usize);
                self.r_out.extend_from_slice(&self.r_inbuf[i..i + take]);
                i += take;
                self.r_rem_content -= take as i32;
            } else if self.r_rem_padding > 0 {
                let take = (n - i).min(self.r_rem_padding as usize);
                i += take;
                self.r_rem_padding -= take as i32;
            }

            if self.r_rem_cmd <= 0 && self.r_rem_content <= 0 && self.r_rem_padding <= 0 {
                if self.r_cur_cmd == CMD_CONTINUE {
                    self.r_rem_cmd = 5;
                    self.r_rem_content = -1;
                    self.r_rem_padding = -1;
                } else {
                    // End keeps the outer transport; Direct bypasses Reality
                    // TLS as well. In both cases Vision padding stops.
                    self.r_padding = false;
                    self.r_direct = self.allow_direct && self.r_cur_cmd == CMD_DIRECT;
                    let rest = self.r_inbuf.split_off(i);
                    self.r_out.extend_from_slice(&rest);
                    self.r_inbuf.clear();
                    return;
                }
            }
        }
        self.r_inbuf.drain(..i);
    }
}

impl<S: AsyncWrite + Unpin> VisionStream<S> {
    /// Drain `w_pending` into the inner stream. Returns `Ready(Ok(()))` only
    /// when fully flushed; back-pressures on `Pending`.
    fn flush_pending(&mut self, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        while !self.w_pending.is_empty() {
            match Pin::new(&mut self.inner).poll_write(cx, &self.w_pending) {
                Poll::Ready(Ok(0)) => {
                    return Poll::Ready(Err(io::Error::new(
                        io::ErrorKind::WriteZero,
                        "vision: inner write returned 0",
                    )));
                }
                Poll::Ready(Ok(n)) => {
                    self.w_pending.drain(..n);
                }
                Poll::Ready(Err(error)) => return Poll::Ready(Err(error)),
                Poll::Pending => return Poll::Pending,
            }
        }
        Poll::Ready(Ok(()))
    }
}

/// xray `XtlsFilterTls` over the de-padded downlink: buffer `produced` until
/// the first inner record is complete, then decide whether it is a TLS 1.3
/// ServerHello (`supported_versions` = 0x0304) with a cipher other than
/// `TLS_AES_128_CCM_8_SHA256`. Returns `None` while undecided.
fn filter_server_hello(buffered: &mut Vec<u8>, produced: &[u8]) -> Option<bool> {
    const SERVER_HELLO_START: [u8; 3] = [TLS_HANDSHAKE, TLS_RECORD_MAJOR_VERSION, 0x03];
    const SERVER_HELLO_TYPE: u8 = 0x02;
    const TLS13_SUPPORTED_VERSIONS: [u8; 6] = [0x00, 0x2b, 0x00, 0x02, 0x03, 0x04];
    const TLS_AES_128_CCM_8_SHA256: [u8; 2] = [0x13, 0x05];
    // Record header, then handshake type (1), length (3), version (2), random (32).
    const SESSION_ID_LEN_OFFSET: usize = 43;

    buffered.extend_from_slice(produced);
    if buffered.len() <= TLS_RECORD_HEADER_LEN {
        return None;
    }
    if buffered[..3] != SERVER_HELLO_START || buffered[TLS_RECORD_HEADER_LEN] != SERVER_HELLO_TYPE {
        *buffered = Vec::new();
        return Some(false);
    }
    let record_end = TLS_RECORD_HEADER_LEN + usize::from(u16::from_be_bytes([buffered[3], buffered[4]]));
    let hello = buffered.get(..record_end)?;
    let tls13 = hello.windows(TLS13_SUPPORTED_VERSIONS.len()).any(|window| window == TLS13_SUPPORTED_VERSIONS);
    let cipher_allows_splice = hello.get(SESSION_ID_LEN_OFFSET).is_some_and(|&session_id_len| {
        let cipher_at = SESSION_ID_LEN_OFFSET + 1 + usize::from(session_id_len);
        hello.get(cipher_at..cipher_at + 2).is_some_and(|cipher| cipher != TLS_AES_128_CCM_8_SHA256)
    });
    *buffered = Vec::new();
    Some(tls13 && cipher_allows_splice)
}

/// Encode a single Vision padding chunk into `out`. Pure (the padding length is
/// supplied) so the wire format is golden-testable.
fn encode_padding_chunk(out: &mut Vec<u8>, uuid: Option<&[u8; 16]>, cmd: u8, content: &[u8], padding: u16) {
    debug_assert!(content.len() <= usize::from(u16::MAX));
    if let Some(uuid) = uuid {
        out.extend_from_slice(uuid);
    }
    out.push(cmd);
    out.extend_from_slice(&(content.len() as u16).to_be_bytes());
    out.extend_from_slice(&padding.to_be_bytes());
    out.extend_from_slice(content);
    out.resize(out.len() + usize::from(padding), 0);
}

impl<S: XtlsDirectRead> AsyncRead for VisionStream<S> {
    fn poll_read(self: Pin<&mut Self>, cx: &mut Context<'_>, read_buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        if !this.vision {
            return Pin::new(&mut this.inner).poll_read(cx, read_buf);
        }
        if read_buf.remaining() == 0 && !this.r_out.is_empty() {
            cx.waker().wake_by_ref();
            return Poll::Pending;
        }
        loop {
            if !this.r_out.is_empty() {
                let n = this.r_out.len().min(read_buf.remaining());
                read_buf.put_slice(&this.r_out[..n]);
                this.r_out.drain(..n);
                return Poll::Ready(Ok(()));
            }
            if this.r_direct {
                // `Direct` removes both Vision padding and the outer Reality
                // TLS record layer. Read from the transport beneath BoringSSL.
                return Pin::new(&mut this.inner).poll_read_direct(cx, read_buf);
            }
            if !this.r_padding {
                return Pin::new(&mut this.inner).poll_read(cx, read_buf);
            }
            let mut scratch = [0u8; READ_SCRATCH];
            let mut scratch_buf = ReadBuf::new(&mut scratch);
            match Pin::new(&mut this.inner).poll_read(cx, &mut scratch_buf) {
                Poll::Pending => return Poll::Pending,
                Poll::Ready(Err(error)) => return Poll::Ready(Err(error)),
                Poll::Ready(Ok(())) => {
                    let filled = scratch_buf.filled();
                    if filled.is_empty() {
                        // Inner EOF; r_out is already drained.
                        return Poll::Ready(Ok(()));
                    }
                    this.r_inbuf.extend_from_slice(filled);
                    let produced_from = this.r_out.len();
                    this.unpad();
                    if this.xtls_enabled.is_none() {
                        this.xtls_enabled = filter_server_hello(&mut this.r_hello, &this.r_out[produced_from..]);
                    }
                }
            }
        }
    }
}

impl<S: XtlsDirectWrite> AsyncWrite for VisionStream<S> {
    fn poll_write(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        let this = self.get_mut();
        if !this.vision {
            return Pin::new(&mut this.inner).poll_write(cx, buf);
        }
        // Flush any padded prefix already encoded before accepting new input.
        match this.flush_pending(cx) {
            Poll::Ready(Ok(())) => {}
            Poll::Ready(Err(error)) => return Poll::Ready(Err(error)),
            Poll::Pending => return Poll::Pending,
        }
        if this.w_direct {
            // The peer switches to the transport beneath Reality TLS after
            // receiving `Direct`, so subsequent inner TLS records bypass it.
            return Pin::new(&mut this.inner).poll_write_direct(cx, buf);
        }
        if !this.w_padding {
            return Pin::new(&mut this.inner).poll_write(cx, buf);
        }
        if buf.is_empty() {
            return Poll::Ready(Ok(0));
        }

        this.w_chunks += 1;
        let reached_appdata = this.note_records(buf);
        let stop = reached_appdata || (!this.w_seen_handshake && this.w_chunks >= NON_TLS_CHUNK_LIMIT);
        // Direct removes the outer Reality TLS, so only inner TLS 1.3 may take
        // it (xray XtlsFilterTls); non-TLS bytes, inner TLS 1.2 and 0-RTT data
        // sent before the ServerHello end with End and stay encrypted.
        let final_cmd =
            if this.allow_direct && reached_appdata && this.xtls_enabled == Some(true) { CMD_DIRECT } else { CMD_END };
        let cmd = if stop { final_cmd } else { CMD_CONTINUE };
        this.encode_outgoing(buf, cmd);
        if stop {
            this.w_padding = false;
            this.w_direct = final_cmd == CMD_DIRECT;
        }

        // Best-effort flush; remaining bytes stay buffered for the next poll.
        if let Poll::Ready(Err(error)) = this.flush_pending(cx) {
            return Poll::Ready(Err(error));
        }
        Poll::Ready(Ok(buf.len()))
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        if this.w_direct {
            match this.flush_pending(cx) {
                Poll::Ready(Ok(())) => {}
                Poll::Ready(Err(error)) => return Poll::Ready(Err(error)),
                Poll::Pending => return Poll::Pending,
            }
            return Pin::new(&mut this.inner).poll_flush_direct(cx);
        }
        if this.vision {
            match this.flush_pending(cx) {
                Poll::Ready(Ok(())) => {}
                Poll::Ready(Err(error)) => return Poll::Ready(Err(error)),
                Poll::Pending => return Poll::Pending,
            }
        }
        Pin::new(&mut this.inner).poll_flush(cx)
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        let this = self.get_mut();
        if this.w_direct {
            match this.flush_pending(cx) {
                Poll::Ready(Ok(())) => {}
                Poll::Ready(Err(error)) => return Poll::Ready(Err(error)),
                Poll::Pending => return Poll::Pending,
            }
            return Pin::new(&mut this.inner).poll_shutdown_direct(cx);
        }
        if this.vision {
            match this.flush_pending(cx) {
                Poll::Ready(Ok(())) => {}
                Poll::Ready(Err(error)) => return Poll::Ready(Err(error)),
                Poll::Pending => return Poll::Pending,
            }
        }
        Pin::new(&mut this.inner).poll_shutdown(cx)
    }
}

#[cfg(test)]
mod tests {
    use std::io::Cursor;
    use std::pin::Pin;
    use std::task::{Context, Poll};

    use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, DuplexStream, ReadBuf};

    use super::*;

    const TEST_UUID: [u8; 16] =
        [0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10];

    impl XtlsDirectRead for Cursor<Vec<u8>> {
        fn poll_read_direct(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
            AsyncRead::poll_read(self, cx, buf)
        }
    }

    impl XtlsDirectWrite for &mut Vec<u8> {
        fn poll_write_direct(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
            AsyncWrite::poll_write(self, cx, buf)
        }

        fn poll_flush_direct(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
            AsyncWrite::poll_flush(self, cx)
        }

        fn poll_shutdown_direct(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
            AsyncWrite::poll_shutdown(self, cx)
        }
    }

    struct WriteModeSpy {
        outer_tls: Vec<u8>,
        direct: Vec<u8>,
    }

    impl AsyncWrite for WriteModeSpy {
        fn poll_write(mut self: Pin<&mut Self>, _cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
            self.outer_tls.extend_from_slice(buf);
            Poll::Ready(Ok(buf.len()))
        }

        fn poll_flush(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
            Poll::Ready(Ok(()))
        }

        fn poll_shutdown(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
            Poll::Ready(Ok(()))
        }
    }

    impl XtlsDirectWrite for WriteModeSpy {
        fn poll_write_direct(mut self: Pin<&mut Self>, _cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
            self.direct.extend_from_slice(buf);
            Poll::Ready(Ok(buf.len()))
        }

        fn poll_flush_direct(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
            Poll::Ready(Ok(()))
        }

        fn poll_shutdown_direct(self: Pin<&mut Self>, _cx: &mut Context<'_>) -> Poll<io::Result<()>> {
            Poll::Ready(Ok(()))
        }
    }

    struct ReadModeSpy {
        outer_tls: Cursor<Vec<u8>>,
        direct: Cursor<Vec<u8>>,
    }

    impl AsyncRead for ReadModeSpy {
        fn poll_read(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
            AsyncRead::poll_read(Pin::new(&mut self.get_mut().outer_tls), cx, buf)
        }
    }

    impl XtlsDirectRead for ReadModeSpy {
        fn poll_read_direct(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
            AsyncRead::poll_read(Pin::new(&mut self.get_mut().direct), cx, buf)
        }
    }

    struct ModeIo {
        outer: DuplexStream,
        direct: DuplexStream,
    }

    impl AsyncRead for ModeIo {
        fn poll_read(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
            Pin::new(&mut self.get_mut().outer).poll_read(cx, buf)
        }
    }

    impl AsyncWrite for ModeIo {
        fn poll_write(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
            Pin::new(&mut self.get_mut().outer).poll_write(cx, buf)
        }

        fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
            Pin::new(&mut self.get_mut().outer).poll_flush(cx)
        }

        fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
            Pin::new(&mut self.get_mut().outer).poll_shutdown(cx)
        }
    }

    impl XtlsDirectRead for ModeIo {
        fn poll_read_direct(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
            Pin::new(&mut self.get_mut().direct).poll_read(cx, buf)
        }
    }

    impl XtlsDirectWrite for ModeIo {
        fn poll_write_direct(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
            Pin::new(&mut self.get_mut().direct).poll_write(cx, buf)
        }

        fn poll_flush_direct(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
            Pin::new(&mut self.get_mut().direct).poll_flush(cx)
        }

        fn poll_shutdown_direct(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
            Pin::new(&mut self.get_mut().direct).poll_shutdown(cx)
        }
    }

    /// # Cancel safety:
    /// NOT cancel-safe: the test owns both endpoints and never cancels a partial frame read.
    async fn assert_padded_write(
        stream: &mut VisionStream<ModeIo>,
        peer: &mut DuplexStream,
        payload: &[u8],
        first: bool,
        expected_command: u8,
    ) {
        stream.write_all(payload).await.expect("write inner record fragment");
        stream.flush().await.expect("flush padded fragment");
        if first {
            let mut uuid = [0; 16];
            peer.read_exact(&mut uuid).await.expect("first chunk UUID");
            assert_eq!(uuid, TEST_UUID);
        }
        let mut header = [0; 5];
        peer.read_exact(&mut header).await.expect("Vision chunk header");
        assert_eq!(header[0], expected_command, "Direct must wait for a complete TLS record");
        assert_eq!(usize::from(u16::from_be_bytes([header[1], header[2]])), payload.len());
        let mut content = vec![0; payload.len()];
        peer.read_exact(&mut content).await.expect("Vision content");
        assert_eq!(content, payload, "padding must preserve all inner bytes");
        let mut padding = vec![0; usize::from(u16::from_be_bytes([header[3], header[4]]))];
        peer.read_exact(&mut padding).await.expect("Vision padding");
    }

    #[tokio::test]
    /// # Cancel safety:
    /// NOT cancel-safe: each case owns its endpoints and completes every frame operation in order.
    async fn direct_waits_for_complete_inner_records_across_writes() {
        let handshake = [0x16, 0x03, 0x01, 0x00, 0x01, 0x01];
        let application = [0x17, 0x03, 0x03, 0x00, 0x04, 0xaa, 0xbb, 0xcc, 0xdd];
        let next_record = [0x17, 0x03, 0x03, 0x00, 0x02, 0xee, 0xff];
        let cases = [
            vec![application[..6].to_vec(), application[6..].to_vec()],
            vec![
                application[..2].to_vec(),
                application[2..4].to_vec(),
                application[4..6].to_vec(),
                application[6..].to_vec(),
            ],
            vec![[application.as_slice(), &next_record[..6]].concat(), next_record[6..].to_vec()],
            vec![application[..6].to_vec(), [&application[6..], next_record.as_slice()].concat()],
            vec![[application.as_slice(), &next_record[..2]].concat(), next_record[2..].to_vec()],
            vec![[application.as_slice(), next_record.as_slice()].concat()],
        ];
        for fragments in cases {
            let (outer, mut outer_peer) = tokio::io::duplex(65_536);
            let (direct, mut direct_peer) = tokio::io::duplex(1024);
            let mut stream = VisionStream::new_vision(ModeIo { outer, direct }, TEST_UUID);
            let hello = server_hello(Some([0x03, 0x04]), TLS_AES_128_GCM_SHA256);
            let mut downlink = Vec::new();
            encode_padding_chunk(&mut downlink, Some(&TEST_UUID), CMD_CONTINUE, &hello, 0);
            outer_peer.write_all(&downlink).await.expect("peer ServerHello");
            let mut received_hello = vec![0; hello.len()];
            stream.read_exact(&mut received_hello).await.expect("read ServerHello");
            assert_eq!(received_hello, hello);
            assert_padded_write(&mut stream, &mut outer_peer, &handshake, true, CMD_CONTINUE).await;
            for (index, fragment) in fragments.iter().enumerate() {
                let command = if index + 1 == fragments.len() { CMD_DIRECT } else { CMD_CONTINUE };
                assert_padded_write(&mut stream, &mut outer_peer, fragment, false, command).await;
                let mut probe = [0; 1];
                let probe_read = direct_peer.read(&mut probe);
                tokio::pin!(probe_read);
                assert!(futures::poll!(&mut probe_read).is_pending(), "no record prefix may escape as raw bytes");
            }
            stream.write_all(&next_record).await.expect("write next complete raw record");
            stream.flush().await.expect("flush direct transport");
            let mut raw = [0; 7];
            direct_peer.read_exact(&mut raw).await.expect("direct record");
            assert_eq!(raw, next_record, "the raw transport must start at a TLS record header");
        }
    }

    #[test]
    fn encode_padding_chunk_first_chunk_matches_golden() {
        // Golden 1: first chunk, UUID prefixed, Continue, 5-byte content,
        // forced paddingLen = 100 (0x0064).
        let mut out = Vec::new();
        let content = [0x16, 0x03, 0x01, 0x00, 0x05];
        encode_padding_chunk(&mut out, Some(&TEST_UUID), CMD_CONTINUE, &content, 100);

        let mut expected = Vec::new();
        expected.extend_from_slice(&TEST_UUID);
        expected.extend_from_slice(&[0x00, 0x00, 0x05, 0x00, 0x64]);
        expected.extend_from_slice(&content);
        expected.extend(std::iter::repeat_n(0u8, 100));
        assert_eq!(out, expected);
        assert_eq!(out.len(), 16 + 5 + 5 + 100);
    }

    #[test]
    fn encode_padding_chunk_later_chunk_matches_golden() {
        // Golden 2: later chunk (no UUID), End, 13-byte content,
        // forced paddingLen = 30 (0x001e).
        let mut out = Vec::new();
        let content = [0x17, 0x03, 0x03, 0x00, 0x08, 0xaa, 0xbb, 0xcc, 0xdd, 0xee, 0xff, 0x11, 0x22];
        let end_command = 0x01;
        encode_padding_chunk(&mut out, None, end_command, &content, 30);

        let mut expected = vec![0x01, 0x00, 0x0d, 0x00, 0x1e];
        expected.extend_from_slice(&content);
        expected.extend(std::iter::repeat_n(0u8, 30));
        assert_eq!(out, expected);
    }

    /// An inner ServerHello record: `version` in `supported_versions` (or no
    /// such extension when `None`) and the given cipher suite.
    fn server_hello(supported_version: Option<[u8; 2]>, cipher: [u8; 2]) -> Vec<u8> {
        let mut body = vec![0x03, 0x03];
        body.extend_from_slice(&[0x5a; 32]);
        body.push(32);
        body.extend_from_slice(&[0x33; 32]);
        body.extend_from_slice(&cipher);
        body.push(0x00);
        let extensions =
            supported_version.map_or_else(Vec::new, |version| vec![0x00, 0x2b, 0x00, 0x02, version[0], version[1]]);
        body.extend_from_slice(&u16::try_from(extensions.len()).expect("extension length").to_be_bytes());
        body.extend_from_slice(&extensions);
        let mut handshake = vec![0x02, 0x00];
        handshake.extend_from_slice(&u16::try_from(body.len()).expect("hello length").to_be_bytes());
        handshake.extend_from_slice(&body);
        let mut record = vec![TLS_HANDSHAKE, 0x03, 0x03];
        record.extend_from_slice(&u16::try_from(handshake.len()).expect("record length").to_be_bytes());
        record.extend_from_slice(&handshake);
        record
    }

    const TLS_AES_128_GCM_SHA256: [u8; 2] = [0x13, 0x01];

    fn tls13_hello_seen() -> Option<bool> {
        filter_server_hello(&mut Vec::new(), &server_hello(Some([0x03, 0x04]), TLS_AES_128_GCM_SHA256))
    }

    #[test]
    fn server_hello_filter_enables_xtls_only_for_tls13_without_ccm8() {
        assert_eq!(tls13_hello_seen(), Some(true));
        let tls12 = server_hello(None, [0xc0, 0x2f]);
        assert_eq!(filter_server_hello(&mut Vec::new(), &tls12), Some(false), "TLS 1.2 must not splice");
        let ccm8 = server_hello(Some([0x03, 0x04]), [0x13, 0x05]);
        assert_eq!(filter_server_hello(&mut Vec::new(), &ccm8), Some(false), "CCM_8 must not splice");
        assert_eq!(filter_server_hello(&mut Vec::new(), b"HTTP/1.1 200 OK\r\n"), Some(false));

        let hello = server_hello(Some([0x03, 0x04]), TLS_AES_128_GCM_SHA256);
        let mut buffered = Vec::new();
        assert_eq!(filter_server_hello(&mut buffered, &hello[..40]), None, "a split record waits for the rest");
        assert_eq!(filter_server_hello(&mut buffered, &hello[40..]), Some(true));
    }

    #[tokio::test]
    /// # Cancel safety:
    /// NOT cancel-safe: the test completes each write before checking the owned output.
    async fn inner_tls12_or_unanswered_handshake_ends_padding_without_direct() {
        let handshake = [TLS_HANDSHAKE, 0x03, 0x01, 0x00, 0x02, 0xaa, 0xbb];
        let appdata = [TLS_APPLICATION_DATA, 0x03, 0x03, 0x00, 0x03, 0x01, 0x02, 0x03];
        let tls12_hello = filter_server_hello(&mut Vec::new(), &server_hello(None, [0xc0, 0x2f]));
        for downlink in [None, tls12_hello] {
            let mut writer =
                VisionStream::new_vision(WriteModeSpy { outer_tls: Vec::new(), direct: Vec::new() }, TEST_UUID);
            writer.xtls_enabled = downlink;
            writer.write_all(&handshake).await.expect("write handshake");
            writer.write_all(&appdata[..6]).await.expect("write partial appdata");
            writer.write_all(&appdata[6..]).await.expect("complete appdata");
            writer.write_all(b"after").await.expect("write after transition");
            writer.flush().await.expect("flush");

            assert!(writer.inner.direct.is_empty(), "{downlink:?}: uplink must stay inside Reality TLS");
            assert!(writer.inner.outer_tls.ends_with(b"after"), "{downlink:?}: End leaves later bytes unpadded");
        }
    }

    #[tokio::test]
    async fn downlink_tls13_server_hello_arms_direct_through_the_read_path() {
        let mut downlink = Vec::new();
        encode_padding_chunk(
            &mut downlink,
            Some(&TEST_UUID),
            CMD_CONTINUE,
            &server_hello(Some([0x03, 0x04]), TLS_AES_128_GCM_SHA256),
            7,
        );
        let mut stream = VisionStream::new_vision(Cursor::new(downlink), TEST_UUID);
        let mut first = [0u8; 4096];
        let _ = stream.read(&mut first).await.expect("read padded ServerHello");

        assert_eq!(stream.xtls_enabled, Some(true));
    }

    #[tokio::test]
    async fn vision_round_trip_recovers_payload_and_switches_to_direct() {
        // Writer pads a fake handshake record then the first application-data
        // record (which triggers Direct), then writes raw bulk data.
        let handshake = [TLS_HANDSHAKE, 0x03, 0x01, 0x00, 0x02, 0xaa, 0xbb];
        let appdata = [TLS_APPLICATION_DATA, 0x03, 0x03, 0x00, 0x03, 0x01, 0x02, 0x03];
        let bulk = b"raw bytes after the vision splice point";

        let mut sink: Vec<u8> = Vec::new();
        {
            let mut writer = VisionStream::new_vision(&mut sink, TEST_UUID);
            writer.xtls_enabled = tls13_hello_seen();
            writer.write_all(&handshake).await.expect("write handshake");
            writer.write_all(&appdata).await.expect("write appdata");
            writer.write_all(bulk).await.expect("write bulk");
            writer.flush().await.expect("flush");
        }

        // The padded prefix must carry the UUID and expand the byte count.
        assert_eq!(&sink[..16], &TEST_UUID, "first chunk must be UUID-prefixed");
        assert!(sink.len() > handshake.len() + appdata.len() + bulk.len(), "padding must expand the prefix");

        let mut reader = VisionStream::new_vision(Cursor::new(sink), TEST_UUID);
        let mut recovered = Vec::new();
        reader.read_to_end(&mut recovered).await.expect("read");

        let mut expected = Vec::new();
        expected.extend_from_slice(&handshake);
        expected.extend_from_slice(&appdata);
        expected.extend_from_slice(bulk);
        assert_eq!(recovered, expected, "round-trip must recover the exact byte stream");
        assert!(reader.r_direct, "reader must switch to direct after the appdata record");
    }

    #[tokio::test]
    async fn direct_command_bypasses_outer_tls_in_both_directions() {
        let handshake = [TLS_HANDSHAKE, 0x03, 0x01, 0x00, 0x02, 0xaa, 0xbb];
        let appdata = [TLS_APPLICATION_DATA, 0x03, 0x03, 0x00, 0x03, 0x01, 0x02, 0x03];
        let raw = b"raw inner TLS after splice";

        let mut writer =
            VisionStream::new_vision(WriteModeSpy { outer_tls: Vec::new(), direct: Vec::new() }, TEST_UUID);
        writer.xtls_enabled = tls13_hello_seen();
        writer.write_all(&handshake).await.expect("write padded handshake");
        writer.write_all(&appdata).await.expect("write direct transition");
        writer.write_all(raw).await.expect("write raw post-splice bytes");
        writer.flush().await.expect("flush direct transport");

        assert!(writer.inner.outer_tls.starts_with(&TEST_UUID), "Vision prefix must traverse outer TLS");
        assert_eq!(writer.inner.direct, raw, "post-Direct uplink must bypass outer TLS");

        let mut transition = Vec::new();
        encode_padding_chunk(&mut transition, Some(&TEST_UUID), CMD_DIRECT, &appdata, 0);
        let mut reader = VisionStream::new_vision(
            ReadModeSpy { outer_tls: Cursor::new(transition), direct: Cursor::new(raw.to_vec()) },
            TEST_UUID,
        );
        let mut recovered = Vec::new();
        reader.read_to_end(&mut recovered).await.expect("read across direct transition");

        let expected = [appdata.as_slice(), raw].concat();
        assert_eq!(recovered, expected, "post-Direct downlink must bypass outer TLS");
    }

    #[tokio::test]
    async fn tls_only_vision_ends_padding_but_never_bypasses_outer_tls() {
        let mut writer =
            VisionStream::new_vision_tls_only(WriteModeSpy { outer_tls: Vec::new(), direct: Vec::new() }, TEST_UUID);

        for payload in [b"xudp-one".as_slice(), b"xudp-two", b"xudp-three", b"xudp-four", b"xudp-secret-five"] {
            writer.write_all(payload).await.expect("write XUDP payload");
        }
        writer.flush().await.expect("flush outer TLS transport");

        assert!(writer.inner.direct.is_empty(), "XUDP payload must never bypass Reality TLS");
        // Past NON_TLS_CHUNK_LIMIT the client sends the End command and stops
        // framing writes: the fifth payload lands raw at the stream tail.
        let end_command = 0x01;
        assert!(
            writer.inner.outer_tls[..writer.inner.outer_tls.len() - 5].contains(&end_command),
            "an End-command chunk must precede the raw tail"
        );
        assert!(
            writer.inner.outer_tls.ends_with(b"xudp-secret-five"),
            "post-End payloads must be written without Vision framing"
        );
    }

    #[tokio::test]
    async fn direct_capable_vision_keeps_non_tls_payload_inside_outer_tls() {
        let mut writer =
            VisionStream::new_vision(WriteModeSpy { outer_tls: Vec::new(), direct: Vec::new() }, TEST_UUID);

        for payload in [b"POST / HTTP/1.1\r\n".as_slice(), b"Host: a\r\n", b"\r\n", b"body-one", b"plain-secret"] {
            writer.write_all(payload).await.expect("write plaintext payload");
        }
        writer.flush().await.expect("flush outer TLS transport");

        assert!(writer.inner.direct.is_empty(), "non-TLS payload must never bypass Reality TLS");
        assert!(writer.inner.outer_tls.ends_with(b"plain-secret"), "post-End payload must stay on outer TLS");
    }

    #[tokio::test]
    async fn non_tls_bytes_that_look_like_record_types_stay_inside_outer_tls() {
        let mut writer =
            VisionStream::new_vision(WriteModeSpy { outer_tls: Vec::new(), direct: Vec::new() }, TEST_UUID);

        // Handshake and application-data type bytes at parsed boundaries, but no TLS version.
        writer.write_all(&[TLS_HANDSHAKE, 0x00, 0x00, 0x00, 0x01, b'a']).await.expect("write first frame");
        writer.write_all(&[TLS_APPLICATION_DATA, 0x00, 0x00, 0x00, 0x01, b'b']).await.expect("write second frame");
        writer.write_all(b"plain-secret").await.expect("write plaintext");
        writer.flush().await.expect("flush outer TLS transport");

        assert!(writer.inner.direct.is_empty(), "lookalike record bytes must not enable Direct");
    }

    #[tokio::test]
    async fn passthrough_mode_is_transparent_in_both_directions() {
        let payload = b"flow=none must not alter the byte stream at all";

        let mut sink: Vec<u8> = Vec::new();
        {
            let mut writer = VisionStream::new_passthrough(&mut sink);
            writer.write_all(payload).await.expect("write");
            writer.flush().await.expect("flush");
        }
        assert_eq!(sink.as_slice(), payload, "passthrough writes must be byte-identical");

        let mut reader = VisionStream::new_passthrough(Cursor::new(payload.to_vec()));
        let mut recovered = Vec::new();
        reader.read_to_end(&mut recovered).await.expect("read");
        assert_eq!(recovered.as_slice(), payload, "passthrough reads must be byte-identical");
    }

    #[test]
    fn note_records_detects_appdata_only_after_handshake() {
        let mut stream = VisionStream::new_vision((), TEST_UUID);
        // Appdata before any handshake is NOT treated as the transition.
        assert!(!stream.note_records(&[TLS_APPLICATION_DATA, 0x03, 0x03, 0x00, 0x00]));
        // A handshake record arms detection...
        assert!(!stream.note_records(&[TLS_HANDSHAKE, 0x03, 0x01, 0x00, 0x00]));
        // ...so the next appdata record now trips it.
        assert!(stream.note_records(&[TLS_APPLICATION_DATA, 0x03, 0x03, 0x00, 0x00]));
    }
}
