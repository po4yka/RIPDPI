/// Two-phase ring buffer matching the semantics of hev-ring-buffer.c.
///
/// # Two-phase read invariant
/// - `reading_bufs()` returns slices WITHOUT advancing `rp`
/// - `read_finish(n)` advances `rp` and decrements `rda_size`
/// - `read_release(n)` decrements `use_size` (separate counter)
///
/// # Two-phase write invariant
/// - `writing_bufs()` returns mutable slices into free space WITHOUT advancing `wp`
/// - `write_finish(n)` advances `wp`, increments `use_size` and `rda_size`
pub struct RingBuffer {
    rp: usize,
    wp: usize,
    rda_size: usize,
    use_size: usize,
    data: Vec<u8>,
}

impl RingBuffer {
    /// Creates a new ring buffer with the given capacity.
    pub fn new(capacity: usize) -> Self {
        Self {
            rp: 0,
            wp: 0,
            rda_size: 0,
            use_size: 0,
            data: vec![0u8; capacity],
        }
    }

    /// Returns the maximum capacity of the buffer.
    pub fn max_size(&self) -> usize {
        self.data.len()
    }

    /// Returns the total bytes occupying the buffer (committed but not yet released).
    pub fn use_size(&self) -> usize {
        self.use_size
    }

    /// Returns the bytes available for reading (`rda_size`).
    /// Decremented by `read_finish`, unchanged by `read_release`.
    pub fn rda_size(&self) -> usize {
        self.rda_size
    }

    /// Returns `true` if `use_size == max_size` (no free space).
    pub fn is_full(&self) -> bool {
        self.use_size == self.data.len()
    }

    /// Returns `true` if `rda_size == 0` (nothing available to read).
    pub fn is_empty(&self) -> bool {
        self.rda_size == 0
    }

    /// Returns writable buffer regions as `(main_region, wrap_region)`.
    ///
    /// Together the two slices span `max_size - use_size` free bytes.
    /// `wrap_region` is empty when free space is contiguous (no wrap).
    /// Both slices are empty when the buffer is full.
    ///
    /// After filling the slices, call [`write_finish`] with the bytes written.
    pub fn writing_bufs(&mut self) -> (&mut [u8], &mut [u8]) {
        let max = self.data.len();
        if self.use_size == max {
            return (&mut [], &mut []);
        }
        let upper_size = max - self.wp;
        let spc_size = max - self.use_size;
        if spc_size <= upper_size {
            // Free space is contiguous from wp forward.
            (&mut self.data[self.wp..self.wp + spc_size], &mut [])
        } else {
            // Free space wraps: main=[wp..max], wrap=[0..spc_size-upper_size].
            let wrap_size = spc_size - upper_size;
            let (left, right) = self.data.split_at_mut(self.wp);
            (right, &mut left[..wrap_size])
        }
    }

    /// Commits `n` bytes as written: advances `wp` and increments `use_size` and `rda_size`.
    pub fn write_finish(&mut self, n: usize) {
        let max = self.data.len();
        self.wp = (self.wp + n) % max;
        self.use_size += n;
        self.rda_size += n;
    }

    /// Returns readable buffer regions as `(main_region, wrap_region)`.
    ///
    /// Together the two slices span `rda_size` bytes.
    /// `wrap_region` is empty when readable data is contiguous (no wrap).
    /// Both slices are empty when `rda_size == 0`.
    ///
    /// After consuming data, call [`read_finish`] then [`read_release`].
    pub fn reading_bufs(&self) -> (&[u8], &[u8]) {
        if self.rda_size == 0 {
            return (&[], &[]);
        }
        let max = self.data.len();
        let upper_size = max - self.rp;
        if self.rda_size <= upper_size {
            (&self.data[self.rp..self.rp + self.rda_size], &[])
        } else {
            // Readable data wraps: main=[rp..max], wrap=[0..rda_size-upper_size].
            let wrap_size = self.rda_size - upper_size;
            (&self.data[self.rp..], &self.data[..wrap_size])
        }
    }

    /// Advances `rp` by `n` and decrements `rda_size` by `n`.
    /// Does NOT touch `use_size` — call [`read_release`] separately.
    pub fn read_finish(&mut self, n: usize) {
        let max = self.data.len();
        self.rp = (self.rp + n) % max;
        self.rda_size -= n;
    }

    /// Decrements `use_size` by `n`.
    /// If `use_size` reaches zero, resets both `rp` and `wp` to zero.
    pub fn read_release(&mut self, n: usize) {
        self.use_size -= n;
        if self.use_size == 0 {
            self.rp = 0;
            self.wp = 0;
        }
    }
}

/// Writes `data` into `buf` using the two-phase write protocol.
///
/// Fills as many bytes as possible (limited by available space) and calls
/// `write_finish`. Returns the number of bytes actually written.
pub fn write_bytes(buf: &mut RingBuffer, data: &[u8]) -> usize {
    let total = {
        let (a, b) = buf.writing_bufs();
        let n_a = a.len().min(data.len());
        a[..n_a].copy_from_slice(&data[..n_a]);
        let n_b = b.len().min(data.len() - n_a);
        b[..n_b].copy_from_slice(&data[n_a..n_a + n_b]);
        n_a + n_b
    };
    buf.write_finish(total);
    total
}

/// Reads and returns all `rda_size` bytes from `buf`.
///
/// Calls `read_finish` and `read_release` on the full `rda_size`, draining
/// the readable window completely.
pub fn read_available(buf: &mut RingBuffer) -> Vec<u8> {
    let out = {
        let (a, b) = buf.reading_bufs();
        let mut out = Vec::with_capacity(a.len() + b.len());
        out.extend_from_slice(a);
        out.extend_from_slice(b);
        out
    };
    let n = out.len();
    buf.read_finish(n);
    buf.read_release(n);
    out
}

#[cfg(test)]
mod tests {
    use super::*;
    use proptest::prelude::*;

    // ---- Test 1: Simple round-trip ----------------------------------------

    #[test]
    fn test_round_trip_basic() {
        let mut buf = RingBuffer::new(16);
        let written = write_bytes(&mut buf, b"hello");
        assert_eq!(written, 5);
        assert_eq!(buf.use_size(), 5);
        assert_eq!(buf.rda_size(), 5);

        let read = read_available(&mut buf);
        assert_eq!(read, b"hello");
        assert_eq!(buf.use_size(), 0);
        assert_eq!(buf.rda_size(), 0);
    }

    #[test]
    fn test_round_trip_full_capacity() {
        let mut buf = RingBuffer::new(8);
        let data: Vec<u8> = (0u8..8).collect();
        let written = write_bytes(&mut buf, &data);
        assert_eq!(written, 8);

        let read = read_available(&mut buf);
        assert_eq!(read, data);
    }

    // ---- Test 2: Full buffer behaviour ------------------------------------

    #[test]
    fn test_write_to_capacity_is_full() {
        let mut buf = RingBuffer::new(8);
        let written = write_bytes(&mut buf, &[0u8; 8]);
        assert_eq!(written, 8);
        assert!(buf.is_full());
        assert_eq!(buf.use_size(), 8);
    }

    #[test]
    fn test_write_to_full_buffer_returns_empty_slices() {
        let mut buf = RingBuffer::new(8);
        write_bytes(&mut buf, &[0u8; 8]);

        // Buffer is full: writing_bufs must return empty slices
        let (a, b) = buf.writing_bufs();
        assert_eq!(a.len(), 0, "main writing slice must be empty when full");
        assert_eq!(b.len(), 0, "wrap writing slice must be empty when full");
    }

    #[test]
    fn test_partial_write_when_near_full() {
        let mut buf = RingBuffer::new(4);
        write_bytes(&mut buf, &[1u8; 3]); // 3 of 4 bytes used
        let written = write_bytes(&mut buf, &[2u8; 4]); // only 1 byte fits
        assert_eq!(written, 1);
    }

    // ---- Test 3: Wrap-around ----------------------------------------------

    #[test]
    fn test_wraparound_write_read() {
        let mut buf = RingBuffer::new(8);

        // Fill buffer completely with 0xAA
        let written = write_bytes(&mut buf, &[0xAAu8; 8]);
        assert_eq!(written, 8);

        // Read 4 bytes (two-phase: read_finish + read_release)
        {
            let (a, _b) = buf.reading_bufs();
            assert!(a.len() >= 4);
        }
        buf.read_finish(4);
        buf.read_release(4);

        // State: rp=4, wp=0 (wraps), use_size=4, rda_size=4
        // Write 4 more bytes of 0xBB, crossing the boundary
        let written2 = write_bytes(&mut buf, &[0xBBu8; 4]);
        assert_eq!(written2, 4);

        // Read all 8 bytes and verify FIFO order
        let data = read_available(&mut buf);
        assert_eq!(data.len(), 8);
        assert_eq!(&data[..4], &[0xAAu8; 4]);
        assert_eq!(&data[4..], &[0xBBu8; 4]);
    }

    #[test]
    fn test_wraparound_multiple_cycles() {
        let mut buf = RingBuffer::new(8);

        for cycle in 0u8..4 {
            let payload = [cycle; 6];
            let written = write_bytes(&mut buf, &payload);
            assert_eq!(written, 6, "cycle {}: write failed", cycle);

            let read = read_available(&mut buf);
            assert_eq!(read, payload, "cycle {}: data mismatch", cycle);
        }
    }

    // ---- Test 4: Two-iovec path for reading --------------------------------

    #[test]
    fn test_two_iovec_reading() {
        // Goal: reading_bufs() must return two non-empty slices.
        //
        // Setup (8-byte buffer):
        //   write 6 bytes of 0xAA  → rp=0, wp=6, use=6, rda=6
        //   read_finish(4)         → rp=4, rda=2  (use still 6)
        //   read_release(4)        → use=2         (rp=4 stays, not zero)
        //   write 4 bytes of 0xBB → wp wraps to 2, use=6, rda=6
        //   reading: rp=4, upper=4, rda=6 > 4 → two iovecs
        let mut buf = RingBuffer::new(8);

        write_bytes(&mut buf, &[0xAAu8; 6]);

        buf.read_finish(4);
        buf.read_release(4);

        write_bytes(&mut buf, &[0xBBu8; 4]);

        let (a, b) = buf.reading_bufs();
        assert!(!a.is_empty(), "main reading slice must be non-empty");
        assert!(
            !b.is_empty(),
            "wrap reading slice must be non-empty (wrap-around case)"
        );
        assert_eq!(a.len() + b.len(), buf.rda_size());

        // Verify content: 2 bytes of 0xAA (the unconsumed part) then 4 bytes of 0xBB
        let mut combined = a.to_vec();
        combined.extend_from_slice(b);
        assert_eq!(&combined[..2], &[0xAAu8; 2]);
        assert_eq!(&combined[2..], &[0xBBu8; 4]);
    }

    // ---- Test 5: Two-iovec path for writing --------------------------------

    #[test]
    fn test_two_iovec_writing() {
        // Goal: writing_bufs() returns two non-empty slices.
        //
        // Setup (8-byte buffer):
        //   write 6 bytes  → wp=6, use=6
        //   read_finish(4) → rp=4, rda=2
        //   read_release(4)→ use=2  (rp stays at 4)
        //   writing: wp=6, upper=2, spc=6 > 2 → two iovecs
        let mut buf = RingBuffer::new(8);

        write_bytes(&mut buf, &[0u8; 6]);
        buf.read_finish(4);
        buf.read_release(4);

        let (a, b) = buf.writing_bufs();
        assert!(!a.is_empty(), "main writing slice must be non-empty");
        assert!(
            !b.is_empty(),
            "wrap writing slice must be non-empty (wrap-around case)"
        );
        assert_eq!(
            a.len() + b.len(),
            6,
            "total writable space must equal max_size - use_size"
        );
    }

    // ---- Test 6: read_finish / read_release invariants ---------------------

    #[test]
    fn test_read_finish_does_not_change_use_size() {
        let mut buf = RingBuffer::new(16);
        write_bytes(&mut buf, b"hello world"); // 11 bytes

        assert_eq!(buf.use_size(), 11);
        assert_eq!(buf.rda_size(), 11);

        // read_finish advances rp and decrements rda_size but NOT use_size
        buf.read_finish(5);
        assert_eq!(buf.use_size(), 11, "read_finish must not change use_size");
        assert_eq!(
            buf.rda_size(),
            6,
            "read_finish must decrement rda_size by 5"
        );

        // read_release decrements use_size
        buf.read_release(5);
        assert_eq!(
            buf.use_size(),
            6,
            "read_release must decrement use_size by 5"
        );
        assert_eq!(buf.rda_size(), 6, "read_release must not change rda_size");
    }

    #[test]
    fn test_read_release_resets_pointers_when_empty() {
        let mut buf = RingBuffer::new(8);
        write_bytes(&mut buf, &[42u8; 4]);

        buf.read_finish(4);
        buf.read_release(4);

        // use_size == 0 → rp and wp must both reset to 0
        assert_eq!(buf.use_size(), 0);
        assert_eq!(buf.rda_size(), 0);

        // After reset, full capacity must be available as a single contiguous region
        let (a, b) = buf.writing_bufs();
        assert_eq!(
            a.len(),
            8,
            "after pointer reset, all 8 bytes must be contiguous"
        );
        assert_eq!(b.len(), 0);
    }

    #[test]
    fn test_read_release_does_not_reset_when_bytes_remain() {
        let mut buf = RingBuffer::new(8);
        write_bytes(&mut buf, &[0u8; 6]);

        // Release only 4 bytes; 2 remain in use
        buf.read_finish(4);
        buf.read_release(4);

        assert_eq!(
            buf.use_size(),
            2,
            "use_size must be 2 after partial release"
        );
        // rp should be 4 (not reset to 0) because use_size != 0
        // Verify: writing_bufs reflects the wrap-around space
        let (a, b) = buf.writing_bufs();
        assert_eq!(a.len() + b.len(), 6, "6 bytes must be free");
    }

    // ---- Test 7: Accessors on empty buffer ---------------------------------

    #[test]
    fn test_empty_buffer_state() {
        let buf = RingBuffer::new(16);
        assert_eq!(buf.max_size(), 16);
        assert_eq!(buf.use_size(), 0);
        assert_eq!(buf.rda_size(), 0);
        assert!(buf.is_empty());
        assert!(!buf.is_full());
    }

    #[test]
    fn test_empty_reading_returns_empty_slices() {
        let buf = RingBuffer::new(16);
        let (a, b) = buf.reading_bufs();
        assert_eq!(a.len(), 0);
        assert_eq!(b.len(), 0);
    }

    #[test]
    fn test_max_size_accessor() {
        for cap in [1, 64, 1024, 65536] {
            let buf = RingBuffer::new(cap);
            assert_eq!(buf.max_size(), cap);
        }
    }

    // ---- Test 8: Capacity invariant ----------------------------------------

    #[test]
    fn test_use_size_never_exceeds_max_size() {
        let mut buf = RingBuffer::new(4);

        // Write more than capacity; only 4 bytes should be accepted
        let w = write_bytes(&mut buf, &[0u8; 10]);
        assert_eq!(w, 4);
        assert_eq!(buf.use_size(), buf.max_size());
    }

    // ---- Proptest: random write/read sequences maintain data integrity ------

    proptest! {
        #[test]
        fn prop_write_read_fifo_integrity(
            ops in prop::collection::vec(
                prop_oneof![
                    // Write op: (true, data)
                    prop::collection::vec(any::<u8>(), 1usize..=256)
                        .prop_map(|v| (true, v)),
                    // Read op: (false, empty)
                    Just((false, vec![])),
                ],
                1usize..=80,
            )
        ) {
            const CAPACITY: usize = 256;
            let mut buf = RingBuffer::new(CAPACITY);
            let mut model: std::collections::VecDeque<u8> = std::collections::VecDeque::new();

            for (is_write, data) in ops {
                if is_write {
                    let space = CAPACITY - buf.use_size();
                    let to_write = data.len().min(space);
                    if to_write > 0 {
                        let written = write_bytes(&mut buf, &data[..to_write]);
                        prop_assert_eq!(written, to_write);
                        for &b in &data[..to_write] {
                            model.push_back(b);
                        }
                    }
                } else if buf.rda_size() > 0 {
                    let rda = buf.rda_size();
                    let read = read_available(&mut buf);
                    prop_assert_eq!(read.len(), rda, "read length must equal rda_size");
                    for (i, &byte) in read.iter().enumerate() {
                        let expected = model.pop_front()
                            .expect("model drained before buffer");
                        prop_assert_eq!(
                            byte, expected,
                            "byte {} mismatch: got 0x{:02X}, expected 0x{:02X}",
                            i, byte, expected
                        );
                    }
                }
            }

            // Verify model and buffer are in sync
            prop_assert_eq!(
                buf.rda_size(),
                model.len(),
                "buffer rda_size must match model length at end"
            );
        }
    }

    proptest! {
        #[test]
        fn prop_use_size_never_exceeds_capacity(
            writes in prop::collection::vec(1usize..=512, 1usize..=20)
        ) {
            const CAPACITY: usize = 128;
            let mut buf = RingBuffer::new(CAPACITY);
            for size in writes {
                let data = vec![0u8; size];
                write_bytes(&mut buf, &data);
                prop_assert!(
                    buf.use_size() <= buf.max_size(),
                    "use_size {} exceeded max_size {}",
                    buf.use_size(),
                    buf.max_size()
                );
            }
        }
    }
}
