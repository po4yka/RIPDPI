use std::io;
use std::pin::Pin;
use std::task::{Context, Poll};

use tokio::io::{AsyncRead, AsyncWrite, ReadBuf};

const TLS_RECORD_HEADER_LEN: usize = 5;
const TLS_HANDSHAKE: u8 = 0x16;
const TLS_APPLICATION_DATA: u8 = 0x17;
const TLS_MAJOR_VERSION: u8 = 0x03;

/// xtls-rprx-vision flow filter.
///
/// Monitors the write stream for the transition from inner TLS Handshake records
/// (type 0x16) to Application Data records (type 0x17), which signals that the
/// inner TLS handshake is complete. After that transition, switches to zero-copy
/// pass-through mode.
pub struct VisionStream<S> {
    inner: S,
    handshake_done: bool,
    /// Partial TLS record header buffer (up to 5 bytes) for boundary detection.
    header_buf: [u8; TLS_RECORD_HEADER_LEN],
    header_buf_len: usize,
    saw_handshake: bool,
    /// Bytes of the current TLS record's payload still to skip from
    /// subsequent writes. Set when a record's declared length exceeds the
    /// bytes remaining in the current `buf`; consumed on the next call so we
    /// don't misparse a payload byte as the next record's content type.
    remaining_payload: usize,
}

impl<S> VisionStream<S> {
    pub fn new(inner: S) -> Self {
        Self {
            inner,
            handshake_done: false,
            header_buf: [0u8; TLS_RECORD_HEADER_LEN],
            header_buf_len: 0,
            saw_handshake: false,
            remaining_payload: 0,
        }
    }

    /// Check a slice of data for TLS record headers.
    /// Returns `true` if we should now switch to pass-through mode.
    fn check_tls_records(&mut self, buf: &[u8]) -> bool {
        let mut pos = 0;

        // Carry-over: skip any payload bytes belonging to a record whose
        // length field declared more bytes than remained in the previous buf.
        if self.remaining_payload > 0 {
            let skip = self.remaining_payload.min(buf.len());
            self.remaining_payload -= skip;
            pos += skip;
        }

        while pos < buf.len() {
            if self.header_buf_len < TLS_RECORD_HEADER_LEN {
                self.header_buf[self.header_buf_len] = buf[pos];
                self.header_buf_len += 1;
                pos += 1;
                continue;
            }

            // Header complete — parse and act on it.
            let content_type = self.header_buf[0];
            let major = self.header_buf[1];
            if major == TLS_MAJOR_VERSION {
                if content_type == TLS_HANDSHAKE {
                    self.saw_handshake = true;
                    let record_len = u16::from_be_bytes([self.header_buf[3], self.header_buf[4]]) as usize;
                    let available = buf.len() - pos;
                    let skip = record_len.min(available);
                    self.remaining_payload = record_len - skip;
                    pos += skip;
                } else if content_type == TLS_APPLICATION_DATA && self.saw_handshake {
                    return true;
                } else {
                    // Any other content type with valid major: skip its payload
                    // like Handshake so we stay aligned to record boundaries.
                    let record_len = u16::from_be_bytes([self.header_buf[3], self.header_buf[4]]) as usize;
                    let available = buf.len() - pos;
                    let skip = record_len.min(available);
                    self.remaining_payload = record_len - skip;
                    pos += skip;
                }
            }
            // Reset header buffer for next record.
            self.header_buf_len = 0;
        }
        false
    }
}

impl<S: AsyncRead + Unpin> AsyncRead for VisionStream<S> {
    fn poll_read(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &mut ReadBuf<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_read(cx, buf)
    }
}

impl<S: AsyncWrite + Unpin> AsyncWrite for VisionStream<S> {
    fn poll_write(self: Pin<&mut Self>, cx: &mut Context<'_>, buf: &[u8]) -> Poll<io::Result<usize>> {
        let this = self.get_mut();

        if !this.handshake_done && this.check_tls_records(buf) {
            this.handshake_done = true;
            tracing::trace!(
                "vision: inner TLS handshake complete (Application Data detected), switching to pass-through"
            );
        }

        Pin::new(&mut this.inner).poll_write(cx, buf)
    }

    fn poll_flush(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_flush(cx)
    }

    fn poll_shutdown(self: Pin<&mut Self>, cx: &mut Context<'_>) -> Poll<io::Result<()>> {
        Pin::new(&mut self.get_mut().inner).poll_shutdown(cx)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Construct a single TLS record: 5-byte header + payload of zeros of declared length.
    fn record(content_type: u8, payload_len: u16) -> Vec<u8> {
        let mut out = vec![content_type, TLS_MAJOR_VERSION, 0x03, (payload_len >> 8) as u8, (payload_len & 0xFF) as u8];
        out.resize(out.len() + payload_len as usize, 0u8);
        out
    }

    fn new_stream() -> VisionStream<()> {
        VisionStream::new(())
    }

    #[test]
    fn handshake_then_appdata_in_one_buf_triggers_transition() {
        let mut s = new_stream();
        let mut buf = record(TLS_HANDSHAKE, 32);
        buf.extend(record(TLS_APPLICATION_DATA, 8));
        assert!(s.check_tls_records(&buf));
    }

    #[test]
    fn appdata_without_prior_handshake_does_not_transition() {
        let mut s = new_stream();
        let buf = record(TLS_APPLICATION_DATA, 8);
        assert!(!s.check_tls_records(&buf));
        assert!(!s.saw_handshake);
    }

    /// Regression: a TLS handshake record whose payload spans the boundary
    /// between two `poll_write` calls must NOT cause the parser to interpret
    /// the middle of the payload as the next record header.
    #[test]
    fn handshake_payload_spanning_buffer_boundary_does_not_misparse() {
        let mut s = new_stream();
        let full = record(TLS_HANDSHAKE, 100);
        // Split inside the payload: first chunk = header + 20 payload bytes,
        // second chunk = remaining 80 payload bytes, then an AppData record.
        let (first, rest) = full.split_at(TLS_RECORD_HEADER_LEN + 20);

        assert!(!s.check_tls_records(first));
        assert_eq!(s.remaining_payload, 80, "must carry the remaining payload across calls");
        assert!(s.saw_handshake);

        let mut second = rest.to_vec();
        second.extend(record(TLS_APPLICATION_DATA, 16));
        // After consuming the carried 80 bytes, the next 5 bytes should be a
        // real AppData record header, triggering the transition.
        assert!(s.check_tls_records(&second));
        assert_eq!(s.remaining_payload, 0);
    }

    /// Regression: a TLS record header split across two writes must complete
    /// from the accumulated bytes, not be misparsed.
    #[test]
    fn record_header_spanning_buffer_boundary_is_assembled_correctly() {
        let mut s = new_stream();
        let full = record(TLS_HANDSHAKE, 50);
        // Split inside the header.
        let (first, rest) = full.split_at(3);
        assert!(!s.check_tls_records(first));
        assert_eq!(s.header_buf_len, 3);

        // Provide the remaining header bytes plus full payload plus AppData.
        let mut second = rest.to_vec();
        second.extend(record(TLS_APPLICATION_DATA, 4));
        assert!(s.check_tls_records(&second));
    }

    /// A payload that exactly fills the buffer (no spillover) must leave
    /// `remaining_payload == 0` and not consume bytes from the next call.
    #[test]
    fn payload_exactly_filling_buffer_leaves_zero_carry() {
        let mut s = new_stream();
        let buf = record(TLS_HANDSHAKE, 10);
        assert!(!s.check_tls_records(&buf));
        assert_eq!(s.remaining_payload, 0);

        // Next call: a fresh AppData record should trigger the transition cleanly.
        let buf2 = record(TLS_APPLICATION_DATA, 4);
        assert!(s.check_tls_records(&buf2));
    }
}
