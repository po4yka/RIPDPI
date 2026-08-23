//! Protocol-agnostic primitives shared by the [`yamux`](super::yamux) and
//! [`sing_mux`](super::sing_mux) frame codecs:
//!
//! * [`StreamIdAllocator`] -- monotonic logical-stream-id allocation. yamux
//!   wants odd ids for the client side; sing-mux just wants monotonic ids.
//! * [`MuxProtocol`] -- which wire multiplexer a session speaks.
//! * [`StreamMailbox`] -- the per-substream inbound buffer. It is a *bounded*
//!   channel in bytes: this is the backpressure mechanism. A slow reader on
//!   one substream fills only its own mailbox; delivering to it reports
//!   [`DeliverOutcome::WouldBlock`] immediately without blocking, so one slow
//!   stream can never wedge delivery to the others.
//!
//! There is deliberately no session driver here yet: nothing demuxes decoded
//! frames into mailboxes, applies per-stream flow control (withholding yamux
//! `WindowUpdate` credit), or schedules keepalives. Until that driver exists,
//! these are building blocks only -- nothing consumes them outside this
//! crate's tests, and no liveness guarantee holds end to end.
//!
//! TODO(author): implement the session driver (demux loop routing frames to
//! per-substream mailboxes, per-protocol flow control and keepalive
//! scheduling) before wiring any outbound backend onto [`MuxProtocol`].

use std::collections::VecDeque;
use std::sync::{Arc, Mutex};

/// Default per-substream inbound mailbox budget, in buffered payload bytes.
///
/// The backpressure bound is *bytes*, not frames: frame payloads are
/// peer-controlled and can be large (the yamux decoder accepts frames up to
/// 16 MiB), so a frame-count cap alone would let a single substream buffer
/// hundreds of megabytes. 256 KiB matches the scale of yamux's initial
/// per-stream flow-control window: enough to absorb normal reader jitter,
/// small enough that a stalled reader hits the bound almost immediately.
pub const DEFAULT_MAILBOX_BUFFERED_BYTES: usize = 256 * 1024;

/// Allocator-overhead bytes charged per buffered frame on top of its payload
/// length when accounting against a mailbox's byte budget.
///
/// A `Vec<u8>` payload costs far more real memory than `len()` for tiny
/// frames (heap header, capacity, deque slot), so byte accounting that
/// charged payload only would let a flood of 1-byte frames allocate ~30x its
/// budget. Charging a fixed per-frame constant keeps degenerate micro-frame
/// floods within the same order as the configured budget.
pub const PER_FRAME_ACCOUNTING_OVERHEAD_BYTES: usize = 32;

/// Which wire multiplexer a session speaks.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MuxProtocol {
    /// hashicorp yamux.
    Yamux,
    /// The legacy internal frame codec ([`super::sing_mux`]). Not the
    /// sing-box sing-mux wire protocol -- see that module's documentation.
    SingMux,
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
            // The internal codec: monotonic, starting at 1 (0 is the
            // keepalive stream).
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
///
/// The bound is a *byte* budget (payload length plus
/// [`PER_FRAME_ACCOUNTING_OVERHEAD_BYTES`] per frame), not a frame count:
/// frame payloads are peer-controlled and can be large, so a frame-count cap
/// alone would allow one slow substream to buffer unbounded memory.
#[derive(Debug, Clone)]
pub struct StreamMailbox {
    inner: Arc<Mutex<MailboxInner>>,
    max_buffered_bytes: usize,
}

#[derive(Debug)]
struct MailboxInner {
    queue: VecDeque<Vec<u8>>,
    /// Total accounting charge of the queued payloads: each contributes its
    /// length plus [`PER_FRAME_ACCOUNTING_OVERHEAD_BYTES`].
    buffered_bytes: usize,
    /// Set once the peer half-closed this substream; the reader drains the
    /// queue and then observes EOF.
    closed: bool,
}

impl StreamMailbox {
    /// Create an empty mailbox with the given buffered-byte budget (clamped
    /// to at least 1).
    pub fn new(max_buffered_bytes: usize) -> Self {
        Self {
            inner: Arc::new(Mutex::new(MailboxInner { queue: VecDeque::new(), buffered_bytes: 0, closed: false })),
            max_buffered_bytes: max_buffered_bytes.max(1),
        }
    }

    /// Try to buffer `payload` for the substream's reader.
    ///
    /// Returns [`DeliverOutcome::WouldBlock`] *without blocking* when the
    /// mailbox is already at its byte budget -- this is what stops one slow
    /// reader from wedging the mux.
    ///
    /// One exception guarantees progress: a single payload larger than the
    /// entire budget is accepted into an *empty* mailbox. Rejecting it would
    /// wedge the substream permanently -- flow control would retry a delivery
    /// that can never fit, no matter how much the reader drains. The budget
    /// therefore bounds steady-state buffering; a lone oversized frame may
    /// transiently exceed it rather than deadlocking the stream.
    pub fn deliver(&self, payload: Vec<u8>) -> DeliverOutcome {
        let charge = payload.len() + PER_FRAME_ACCOUNTING_OVERHEAD_BYTES;
        let mut inner = self.inner.lock().expect("mailbox mutex poisoned");
        if inner.closed {
            return DeliverOutcome::UnknownStream;
        }
        if inner.buffered_bytes > 0 && inner.buffered_bytes + charge > self.max_buffered_bytes {
            return DeliverOutcome::WouldBlock;
        }
        inner.buffered_bytes += charge;
        inner.queue.push_back(payload);
        DeliverOutcome::Delivered
    }

    /// Pull the next buffered payload, if any. `None` means "nothing buffered
    /// right now" (the reader should await more) unless [`is_closed`] is also
    /// true, in which case `None` is a definitive EOF.
    pub fn take(&self) -> Option<Vec<u8>> {
        let mut inner = self.inner.lock().expect("mailbox mutex poisoned");
        let payload = inner.queue.pop_front()?;
        inner.buffered_bytes -= payload.len() + PER_FRAME_ACCOUNTING_OVERHEAD_BYTES;
        Some(payload)
    }

    /// Number of payloads currently buffered.
    pub fn len(&self) -> usize {
        self.inner.lock().expect("mailbox mutex poisoned").queue.len()
    }

    /// True when no payloads are buffered.
    pub fn is_empty(&self) -> bool {
        self.len() == 0
    }

    /// Current accounting charge of the buffered payloads (payload bytes plus
    /// the per-frame overhead constant).
    pub fn buffered_bytes(&self) -> usize {
        self.inner.lock().expect("mailbox mutex poisoned").buffered_bytes
    }

    /// True when the mailbox has reached its byte budget -- the signal the
    /// session driver uses to withhold this substream's flow-control credit.
    pub fn is_full(&self) -> bool {
        let inner = self.inner.lock().expect("mailbox mutex poisoned");
        inner.buffered_bytes > 0 && inner.buffered_bytes >= self.max_buffered_bytes
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

    /// The configured buffered-byte budget.
    pub fn max_buffered_bytes(&self) -> usize {
        self.max_buffered_bytes
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

    // --- StreamMailbox: the backpressure primitive --------------------------

    #[test]
    fn mailbox_delivers_until_byte_budget_then_would_block() {
        // Budget of exactly two 3-byte frames' worth of accounting charge
        // (payload + per-frame overhead each).
        let mailbox = StreamMailbox::new(2 * (3 + PER_FRAME_ACCOUNTING_OVERHEAD_BYTES));
        assert_eq!(mailbox.deliver(b"one".to_vec()), DeliverOutcome::Delivered);
        assert_eq!(mailbox.deliver(b"two".to_vec()), DeliverOutcome::Delivered);
        // Third delivery into a full mailbox must NOT block -- it reports
        // WouldBlock immediately so the demux loop keeps serving other streams.
        assert_eq!(mailbox.deliver(b"three".to_vec()), DeliverOutcome::WouldBlock);
        assert_eq!(mailbox.buffered_bytes(), 2 * (3 + PER_FRAME_ACCOUNTING_OVERHEAD_BYTES));
        assert!(mailbox.is_full());
    }

    #[test]
    fn mailbox_take_drains_in_fifo_order() {
        let mailbox = StreamMailbox::new(128);
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

    /// Regression test for the frame-count backpressure hole: the mailbox
    /// bound used to be a *frame count* (64 frames) while peer-controlled
    /// frame payloads may be up to 16 MiB (the yamux decoder cap), so one
    /// slow substream could buffer ~1 GiB. The bound is bytes now: two
    /// 40 KiB frames already exhaust a 64 KiB budget even though the frame
    /// count is only 2.
    #[test]
    fn mailbox_is_bounded_in_bytes_not_frames() {
        let mailbox = StreamMailbox::new(64 * 1024);
        let frame = vec![0_u8; 40 * 1024];
        assert_eq!(mailbox.deliver(frame.clone()), DeliverOutcome::Delivered);
        // Frame count is only 2, far under any conceivable frame-count cap --
        // yet the byte budget is exhausted and backpressure kicks in.
        assert_eq!(mailbox.deliver(frame), DeliverOutcome::WouldBlock);

        // A budget-sized frame fills the mailbox exactly once, accepted via
        // the oversized-frame progress rule.
        let full = StreamMailbox::new(64 * 1024);
        assert_eq!(full.deliver(vec![0_u8; 64 * 1024]), DeliverOutcome::Delivered);
        assert!(full.is_full());

        // Draining releases the budget exactly.
        let taken = mailbox.take().expect("buffered frame");
        assert_eq!(taken.len(), 40 * 1024);
        assert_eq!(mailbox.buffered_bytes(), 0);
        assert!(!mailbox.is_full());
    }

    /// A single payload larger than the whole budget must still be accepted
    /// into an empty mailbox: rejecting it would wedge the substream forever,
    /// because per-stream flow control would retry a delivery that can never
    /// fit no matter how much the reader drains.
    #[test]
    fn single_oversized_frame_still_makes_progress() {
        let mailbox = StreamMailbox::new(1024);
        let oversized = vec![0_u8; 100_000];
        assert_eq!(mailbox.deliver(oversized), DeliverOutcome::Delivered, "empty mailbox accepts an oversized frame");
        assert_eq!(mailbox.deliver(vec![0_u8]), DeliverOutcome::WouldBlock);

        assert!(mailbox.take().is_some());
        assert_eq!(mailbox.buffered_bytes(), 0);
        assert_eq!(mailbox.deliver(b"fits now".to_vec()), DeliverOutcome::Delivered);
    }

    /// Per-frame accounting overhead keeps degenerate micro-frame floods --
    /// thousands of 1-byte frames whose payload bytes sum far below the
    /// budget -- from allocating memory in excess of the budget's order of
    /// magnitude.
    #[test]
    fn micro_frame_flood_is_charged_per_frame_overhead() {
        let mailbox = StreamMailbox::new(64);
        assert_eq!(mailbox.deliver(vec![0_u8]), DeliverOutcome::Delivered);
        assert_eq!(mailbox.buffered_bytes(), 1 + PER_FRAME_ACCOUNTING_OVERHEAD_BYTES);
        // A second 1-byte frame would push the accounting charge past the
        // 64-byte budget even though the raw payload total is only 2 bytes.
        assert_eq!(mailbox.deliver(vec![0_u8]), DeliverOutcome::WouldBlock);
        assert_eq!(mailbox.len(), 1);
    }
}
