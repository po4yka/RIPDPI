//! Protocol-agnostic wire-multiplexing session driver.
//!
//! The [`yamux`](super::yamux) and [`sing_mux`](super::sing_mux) modules are
//! pure frame codecs. This module sits on top of them and provides the parts
//! that are common to *both* protocols:
//!
//! * [`StreamIdAllocator`] -- monotonic logical-stream-id allocation. yamux
//!   wants odd ids for the client side; sing-mux just wants monotonic ids.
//! * [`MuxLimits`] -- the configurable caps an outbound profile carries:
//!   max concurrent streams, a per-connection KB/s target hint, and the
//!   sing-mux padding mode.
//! * [`MuxTransport`] -- the trait an outbound crate (VLESS, Trojan, VMess)
//!   plugs either protocol into. It hands back an `AsyncRead + AsyncWrite`
//!   logical stream per `open_stream` call.
//! * [`StreamMailbox`] -- the per-substream inbound buffer. It is a *bounded*
//!   channel: this is the backpressure mechanism. A slow reader on one
//!   substream fills only its own mailbox; the demux loop parks that one
//!   substream's delivery without blocking frame delivery to the others, so
//!   one slow stream can never wedge the whole mux. See
//!   [`StreamMailbox::deliver`] for the precise semantics.
//!
//! # Backpressure
//!
//! Each logical substream owns a bounded mailbox. When the demux loop decodes
//! a `Data`/`New` frame it routes the payload to that substream's mailbox via
//! [`StreamMailbox::deliver`]. If the mailbox is full (the substream's reader
//! is slow), `deliver` returns [`DeliverOutcome::WouldBlock`] *immediately*
//! rather than blocking -- the session driver then applies protocol-level
//! flow control to that one stream (yamux: withhold `WindowUpdate`; sing-mux:
//! stop reading that stream's frames) while continuing to service every other
//! substream. The whole-mux liveness invariant is therefore: *no single
//! substream's reader can stall delivery to a different substream.*

use std::collections::VecDeque;
use std::sync::{Arc, Mutex};

use super::sing_mux::PaddingMode;

/// Default cap on concurrently open logical substreams over one mux session.
pub const DEFAULT_MAX_STREAMS: usize = 256;

/// Default per-substream inbound mailbox capacity, in frames. Small enough
/// that a stalled reader is detected quickly, large enough to absorb normal
/// jitter.
pub const DEFAULT_MAILBOX_CAPACITY: usize = 64;

/// Which wire multiplexer a session speaks.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MuxProtocol {
    /// sing-box's sing-mux.
    SingMux,
    /// hashicorp yamux.
    Yamux,
}

/// Monotonic logical-stream-id allocator.
///
/// yamux requires the connection initiator to use odd stream ids (and never
/// reuse `0`, which is the session control stream). sing-mux only requires
/// monotonic ids. [`StreamIdAllocator::for_protocol`] picks the right
/// starting point and step for either.
#[derive(Debug)]
pub struct StreamIdAllocator {
    /// The next id to hand out, or `None` once the id space is exhausted.
    next: Option<u32>,
    step: u32,
}

impl StreamIdAllocator {
    /// Build an allocator appropriate for `protocol` on the *initiator* side
    /// of the connection.
    pub fn for_protocol(protocol: MuxProtocol) -> Self {
        match protocol {
            // yamux: client streams are odd, starting at 1.
            MuxProtocol::Yamux => Self { next: Some(1), step: 2 },
            // sing-mux: monotonic, starting at 1 (0 is the keepalive stream).
            MuxProtocol::SingMux => Self { next: Some(1), step: 1 },
        }
    }

    /// Allocate the next logical stream id, or `None` once the id space is
    /// exhausted (a connection that has opened ~4 billion streams should be
    /// recycled rather than wrapped). The final valid id is still returned
    /// before exhaustion is reported.
    pub fn allocate(&mut self) -> Option<u32> {
        let id = self.next?;
        // Advance for next time; once this overflows, the id space is spent.
        self.next = id.checked_add(self.step);
        Some(id)
    }

    /// The id the next [`allocate`](Self::allocate) call will return, or
    /// `None` once the id space is exhausted.
    pub fn peek(&self) -> Option<u32> {
        self.next
    }
}

/// Configurable per-session multiplexing limits, carried by an outbound
/// profile (`mux` config block).
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct MuxLimits {
    /// Maximum number of substreams open concurrently over one mux session.
    /// A request to open beyond this is refused so a single session cannot
    /// be used to exhaust server resources.
    pub max_concurrent_streams: usize,
    /// Soft per-connection throughput target hint, in KB/s. `None` means
    /// "unthrottled". The session driver uses this to pace `WindowUpdate` /
    /// `KeepAlive` emission; it is a hint, not a hard rate limiter.
    pub per_connection_kbps: Option<u32>,
    /// sing-mux padding mode (ignored by yamux sessions).
    pub padding_mode: PaddingMode,
    /// Per-substream inbound mailbox capacity, in frames -- the backpressure
    /// depth before a slow reader triggers per-stream flow control.
    pub mailbox_capacity: usize,
}

impl Default for MuxLimits {
    fn default() -> Self {
        Self {
            max_concurrent_streams: DEFAULT_MAX_STREAMS,
            per_connection_kbps: None,
            padding_mode: PaddingMode::Disabled,
            mailbox_capacity: DEFAULT_MAILBOX_CAPACITY,
        }
    }
}

impl MuxLimits {
    /// Builder: cap concurrent substreams.
    pub fn with_max_concurrent_streams(mut self, max: usize) -> Self {
        self.max_concurrent_streams = max;
        self
    }

    /// Builder: set the per-connection KB/s target hint.
    pub fn with_per_connection_kbps(mut self, kbps: u32) -> Self {
        self.per_connection_kbps = Some(kbps);
        self
    }

    /// Builder: select the sing-mux padding mode.
    pub fn with_padding_mode(mut self, padding_mode: PaddingMode) -> Self {
        self.padding_mode = padding_mode;
        self
    }

    /// Builder: set the per-substream mailbox capacity.
    pub fn with_mailbox_capacity(mut self, capacity: usize) -> Self {
        self.mailbox_capacity = capacity.max(1);
        self
    }
}

/// Errors a [`MuxTransport`] can produce.
#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum MuxError {
    /// Opening another substream would exceed [`MuxLimits::max_concurrent_streams`].
    #[error("mux session at capacity: {open} of {max} substreams already open")]
    AtCapacity {
        /// Substreams currently open.
        open: usize,
        /// Configured maximum.
        max: usize,
    },
    /// The logical-stream-id space is exhausted; the session must be recycled.
    #[error("mux stream id space exhausted; recycle the session")]
    StreamIdExhausted,
    /// The underlying connection carrying the mux session has failed.
    #[error("mux session connection closed")]
    SessionClosed,
}

/// The trait an outbound crate plugs a concrete wire multiplexer into.
///
/// VLESS / Trojan / VMess outbounds hold a `dyn MuxTransport` (or a generic
/// `M: MuxTransport`) and call [`open_stream`](Self::open_stream) per logical
/// flow. The associated [`Stream`](Self::Stream) is the `AsyncRead +
/// AsyncWrite` surface the outbound then layers its own protocol framing on.
pub trait MuxTransport {
    /// The per-substream byte surface handed back by `open_stream`.
    type Stream;

    /// Which wire protocol this transport speaks.
    fn protocol(&self) -> MuxProtocol;

    /// The limits this session was configured with.
    fn limits(&self) -> MuxLimits;

    /// Number of substreams currently open.
    fn open_stream_count(&self) -> usize;

    /// Open a new logical substream for `destination` (e.g. `"host:443"`).
    ///
    /// Fails with [`MuxError::AtCapacity`] when the configured concurrency
    /// cap is reached, or [`MuxError::StreamIdExhausted`] once the id space
    /// runs out.
    fn open_stream(&mut self, destination: &str) -> Result<Self::Stream, MuxError>;
}

/// Outcome of routing one inbound frame into a substream's mailbox.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DeliverOutcome {
    /// The payload was buffered for the substream's reader.
    Delivered,
    /// The substream's mailbox is full -- its reader is slow. The caller
    /// applies per-stream flow control and retries later. Critically, this
    /// is returned *immediately* (never blocks), so a slow reader on one
    /// substream cannot stall the demux loop for the others.
    WouldBlock,
    /// The substream id is unknown (already closed, or never opened).
    UnknownStream,
}

/// A single logical substream's bounded inbound buffer.
///
/// This is the concrete backpressure primitive. The demux loop owns one
/// `StreamMailbox` per open substream and routes decoded payloads into them
/// with [`deliver`](Self::deliver). A substream's reader drains it with
/// [`take`](Self::take). Because the buffer is bounded and `deliver` is
/// non-blocking, the whole-mux liveness invariant holds: a slow reader fills
/// only its own mailbox and never blocks delivery to a different substream.
#[derive(Debug, Clone)]
pub struct StreamMailbox {
    inner: Arc<Mutex<MailboxInner>>,
    capacity: usize,
}

#[derive(Debug)]
struct MailboxInner {
    queue: VecDeque<Vec<u8>>,
    /// Set once the peer half-closed this substream; the reader drains the
    /// queue and then observes EOF.
    closed: bool,
}

impl StreamMailbox {
    /// Create an empty mailbox with the given frame capacity (clamped to at
    /// least 1).
    pub fn new(capacity: usize) -> Self {
        Self {
            inner: Arc::new(Mutex::new(MailboxInner { queue: VecDeque::new(), closed: false })),
            capacity: capacity.max(1),
        }
    }

    /// Try to buffer `payload` for the substream's reader.
    ///
    /// Returns [`DeliverOutcome::WouldBlock`] *without blocking* when the
    /// mailbox is already at capacity -- this is what stops one slow reader
    /// from wedging the mux.
    pub fn deliver(&self, payload: Vec<u8>) -> DeliverOutcome {
        let mut inner = self.inner.lock().expect("mailbox mutex poisoned");
        if inner.closed {
            return DeliverOutcome::UnknownStream;
        }
        if inner.queue.len() >= self.capacity {
            return DeliverOutcome::WouldBlock;
        }
        inner.queue.push_back(payload);
        DeliverOutcome::Delivered
    }

    /// Pull the next buffered payload, if any. `None` means "nothing buffered
    /// right now" (the reader should await more) unless [`is_closed`] is also
    /// true, in which case `None` is a definitive EOF.
    pub fn take(&self) -> Option<Vec<u8>> {
        self.inner.lock().expect("mailbox mutex poisoned").queue.pop_front()
    }

    /// Number of payloads currently buffered.
    pub fn len(&self) -> usize {
        self.inner.lock().expect("mailbox mutex poisoned").queue.len()
    }

    /// True when no payloads are buffered.
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// True when the mailbox has reached capacity -- the signal the session
    /// driver uses to withhold this substream's flow-control credit.
    pub fn is_full(&self) -> bool {
        self.len() >= self.capacity
    }

    /// Mark the substream half-closed by the peer. The reader still drains
    /// any already-buffered payloads, then observes EOF.
    pub fn close(&self) {
        self.inner.lock().expect("mailbox mutex poisoned").closed = true;
    }

    /// True once the peer has half-closed this substream.
    pub fn is_closed(&self) -> bool {
        self.inner.lock().expect("mailbox mutex poisoned").closed
    }

    /// The configured frame capacity.
    pub fn capacity(&self) -> usize {
        self.capacity
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // --- StreamIdAllocator --------------------------------------------------

    #[test]
    fn yamux_allocator_yields_odd_ids() {
        let mut alloc = StreamIdAllocator::for_protocol(MuxProtocol::Yamux);
        assert_eq!(alloc.allocate(), Some(1));
        assert_eq!(alloc.allocate(), Some(3));
        assert_eq!(alloc.allocate(), Some(5));
        assert_eq!(alloc.peek(), Some(7));
    }

    #[test]
    fn sing_mux_allocator_yields_monotonic_ids() {
        let mut alloc = StreamIdAllocator::for_protocol(MuxProtocol::SingMux);
        assert_eq!(alloc.allocate(), Some(1));
        assert_eq!(alloc.allocate(), Some(2));
        assert_eq!(alloc.allocate(), Some(3));
    }

    #[test]
    fn allocator_reports_exhaustion_instead_of_wrapping() {
        // Seeded one id below the ceiling: the final valid id is still
        // returned, then exhaustion is reported rather than the id wrapping.
        let mut alloc = StreamIdAllocator { next: Some(u32::MAX), step: 2 };
        assert_eq!(alloc.allocate(), Some(u32::MAX), "the final valid id is still handed out");
        assert_eq!(alloc.peek(), None, "id space is now exhausted");
        assert_eq!(alloc.allocate(), None, "must not wrap the id space");
    }

    // --- MuxLimits ----------------------------------------------------------

    #[test]
    fn mux_limits_default_is_unthrottled_and_unpadded() {
        let limits = MuxLimits::default();
        assert_eq!(limits.max_concurrent_streams, DEFAULT_MAX_STREAMS);
        assert_eq!(limits.per_connection_kbps, None);
        assert_eq!(limits.padding_mode, PaddingMode::Disabled);
        assert_eq!(limits.mailbox_capacity, DEFAULT_MAILBOX_CAPACITY);
    }

    #[test]
    fn mux_limits_builder_sets_every_field() {
        let limits = MuxLimits::default()
            .with_max_concurrent_streams(8)
            .with_per_connection_kbps(2048)
            .with_padding_mode(PaddingMode::Enabled { max: 256 })
            .with_mailbox_capacity(16);
        assert_eq!(limits.max_concurrent_streams, 8);
        assert_eq!(limits.per_connection_kbps, Some(2048));
        assert_eq!(limits.padding_mode, PaddingMode::Enabled { max: 256 });
        assert_eq!(limits.mailbox_capacity, 16);
    }

    #[test]
    fn mux_limits_mailbox_capacity_clamps_to_at_least_one() {
        assert_eq!(MuxLimits::default().with_mailbox_capacity(0).mailbox_capacity, 1);
    }

    // --- StreamMailbox: the backpressure primitive --------------------------

    #[test]
    fn mailbox_delivers_until_capacity_then_would_block() {
        let mailbox = StreamMailbox::new(2);
        assert_eq!(mailbox.deliver(b"one".to_vec()), DeliverOutcome::Delivered);
        assert_eq!(mailbox.deliver(b"two".to_vec()), DeliverOutcome::Delivered);
        // Third delivery into a full mailbox must NOT block -- it reports
        // WouldBlock immediately so the demux loop keeps serving other streams.
        assert_eq!(mailbox.deliver(b"three".to_vec()), DeliverOutcome::WouldBlock);
        assert!(mailbox.is_full());
    }

    #[test]
    fn mailbox_take_drains_in_fifo_order() {
        let mailbox = StreamMailbox::new(4);
        mailbox.deliver(b"first".to_vec());
        mailbox.deliver(b"second".to_vec());
        assert_eq!(mailbox.take().as_deref(), Some(&b"first"[..]));
        assert_eq!(mailbox.take().as_deref(), Some(&b"second"[..]));
        assert_eq!(mailbox.take(), None);
    }

    #[test]
    fn draining_a_full_mailbox_reopens_it_for_delivery() {
        let mailbox = StreamMailbox::new(1);
        assert_eq!(mailbox.deliver(b"a".to_vec()), DeliverOutcome::Delivered);
        assert_eq!(mailbox.deliver(b"b".to_vec()), DeliverOutcome::WouldBlock);
        // Reader catches up...
        assert_eq!(mailbox.take().as_deref(), Some(&b"a"[..]));
        // ...and the mailbox accepts again. Backpressure released.
        assert_eq!(mailbox.deliver(b"b".to_vec()), DeliverOutcome::Delivered);
    }

    #[test]
    fn closed_mailbox_still_drains_buffered_payloads_then_eofs() {
        let mailbox = StreamMailbox::new(4);
        mailbox.deliver(b"buffered".to_vec());
        mailbox.close();
        assert!(mailbox.is_closed());
        // Buffered data is still readable...
        assert_eq!(mailbox.take().as_deref(), Some(&b"buffered"[..]));
        // ...then take() returns None and is_closed() makes it a definitive EOF.
        assert_eq!(mailbox.take(), None);
        // A late delivery into a closed mailbox is dropped, not buffered.
        assert_eq!(mailbox.deliver(b"late".to_vec()), DeliverOutcome::UnknownStream);
    }

    /// The whole-mux liveness invariant, expressed as a unit test: a slow
    /// reader on substream A (mailbox full) does NOT prevent delivery to
    /// substream B. Both mailboxes are independent, and `deliver` never
    /// blocks, so the demux loop stays live for B regardless of A.
    #[test]
    fn slow_reader_on_one_stream_does_not_wedge_another() {
        let slow_stream = StreamMailbox::new(1);
        let fast_stream = StreamMailbox::new(1);

        // Substream A's reader has stalled: its mailbox is full.
        assert_eq!(slow_stream.deliver(b"A1".to_vec()), DeliverOutcome::Delivered);
        assert_eq!(slow_stream.deliver(b"A2".to_vec()), DeliverOutcome::WouldBlock);

        // The demux loop now routes a frame for substream B. Because the
        // mailboxes are independent and `deliver` is non-blocking, B is
        // serviced normally even though A is wedged.
        assert_eq!(fast_stream.deliver(b"B1".to_vec()), DeliverOutcome::Delivered);
        assert_eq!(fast_stream.take().as_deref(), Some(&b"B1"[..]));

        // A is still backed up -- its backpressure is contained to A alone.
        assert!(slow_stream.is_full());
    }

    #[test]
    fn mailbox_clones_share_the_same_queue() {
        // The demux loop holds one clone, the substream reader holds another;
        // they must see the same underlying queue.
        let writer_side = StreamMailbox::new(4);
        let reader_side = writer_side.clone();
        writer_side.deliver(b"shared".to_vec());
        assert_eq!(reader_side.len(), 1);
        assert_eq!(reader_side.take().as_deref(), Some(&b"shared"[..]));
        assert!(writer_side.is_empty());
    }
}
