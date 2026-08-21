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
//! so chunk boundaries need not align with TLS records. Padding stops once the
//! first inner Application-Data (`0x17`) record is observed: that chunk carries
//! `Direct` (the xtls splice signal), and the stream is raw afterwards in that
//! direction. The two directions transition independently.
//!
//! RIPDPI writes the VLESS request header eagerly before this wrapper engages,
//! so the xray "hide-header" zero-content chunk is not emitted. The UUID still
//! prefixes the first real chunk. For inner TLS, `Direct` switches reads and
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
const TLS_HANDSHAKE: u8 = 0x16;
const TLS_APPLICATION_DATA: u8 = 0x17;
const TLS_RECORD_HEADER_LEN: usize = 5;

/// Maximum content bytes per chunk — xray's `buf.Size - 21` (8192 minus the
/// 16-byte UUID and the 5-byte command block). Larger writes are split into
/// multiple chunks; in practice a relay write is <= 8 KiB so this rarely trips.
const MAX_CHUNK_CONTENT: usize = 8192 - 21;

/// Stop padding a non-TLS stream after this many chunks without a ClientHello,
/// mirroring xray's bounded TLS-detection window. A real TLS handshake flags
/// itself on its first record, so this only trips for non-TLS traffic (which
/// still works — it is simply sent raw after a short padded prefix).
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
    /// writes. Returns `true` once the first inner application-data record
    /// (after at least one handshake record) begins in this buffer — the signal
    /// to stop padding.
    fn note_records(&mut self, buf: &[u8]) -> bool {
        let mut pos = 0;
        let mut reached = false;
        while pos < buf.len() {
            if self.w_rec_remaining > 0 {
                let skip = self.w_rec_remaining.min(buf.len() - pos);
                self.w_rec_remaining -= skip;
                pos += skip;
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
            let record_len = usize::from(u16::from_be_bytes([self.w_hdr[3], self.w_hdr[4]]));
            if record_type == TLS_APPLICATION_DATA && self.w_seen_handshake {
                reached = true;
            }
            if record_type == TLS_HANDSHAKE {
                self.w_seen_handshake = true;
            }
            self.w_rec_remaining = record_len;
            self.w_hdr_len = 0;
            if reached {
                break;
            }
        }
        reached
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
                    this.unpad();
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
        let final_cmd = if this.allow_direct { CMD_DIRECT } else { CMD_END };
        let cmd = if stop { final_cmd } else { CMD_CONTINUE };
        this.encode_outgoing(buf, cmd);
        if stop {
            this.w_padding = false;
            this.w_direct = this.allow_direct;
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

    use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt, ReadBuf};

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
