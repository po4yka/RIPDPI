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
}

impl<S> VisionStream<S> {
    pub fn new(inner: S) -> Self {
        Self {
            inner,
            handshake_done: false,
            header_buf: [0u8; TLS_RECORD_HEADER_LEN],
            header_buf_len: 0,
            saw_handshake: false,
        }
    }

    /// Check a slice of data for TLS record headers.
    /// Returns `true` if we should now switch to pass-through mode.
    fn check_tls_records(&mut self, buf: &[u8]) -> bool {
        let mut pos = 0;

        // First drain any partial header we've been accumulating
        while pos < buf.len() {
            if self.header_buf_len < TLS_RECORD_HEADER_LEN {
                self.header_buf[self.header_buf_len] = buf[pos];
                self.header_buf_len += 1;
                pos += 1;
            }

            if self.header_buf_len == TLS_RECORD_HEADER_LEN {
                let content_type = self.header_buf[0];
                let major = self.header_buf[1];
                if major == TLS_MAJOR_VERSION {
                    if content_type == TLS_HANDSHAKE {
                        self.saw_handshake = true;
                        // Skip past this record's payload
                        let record_len = u16::from_be_bytes([self.header_buf[3], self.header_buf[4]]) as usize;
                        pos = pos.saturating_add(record_len);
                    } else if content_type == TLS_APPLICATION_DATA && self.saw_handshake {
                        // Transition: inner handshake done
                        return true;
                    }
                }
                // Reset header buffer for next record
                self.header_buf_len = 0;
                self.header_buf = [0u8; TLS_RECORD_HEADER_LEN];
            } else {
                // Need more bytes to complete the header
                break;
            }
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
