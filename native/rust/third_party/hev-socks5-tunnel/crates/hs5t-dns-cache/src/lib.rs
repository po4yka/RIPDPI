use std::collections::HashMap;
use std::num::NonZeroUsize;

use lru::LruCache;
use thiserror::Error;

#[derive(Debug, Error, PartialEq, Eq)]
pub enum DnsCacheError {
    #[error("response buffer smaller than request")]
    BufferTooSmall,
    #[error("too many questions in DNS query (max 32)")]
    TooManyQuestions,
    #[error("truncated or malformed DNS packet")]
    Truncated,
}

/// LRU DNS cache that maps domain names to synthetic IPv4 addresses.
///
/// Replicates the behaviour of `hev_mapped_dns` from the C reference
/// implementation: addresses are allocated from the range `[net, net+max)`,
/// with LRU eviction when the cache is full.
pub struct DnsCache {
    lru: LruCache<String, usize>,
    rev: HashMap<u32, String>,
    records: Vec<Option<String>>,
    net: u32,
    mask: u32,
    max: usize,
    next_free: usize,
}

impl DnsCache {
    /// Create a new DNS cache.
    ///
    /// `net`  – network address prefix (e.g. `0x0a000000` for 10.0.0.0)
    /// `mask` – network mask (e.g. `0xffffff00` for /24)
    /// `max`  – maximum number of cached entries; must satisfy `max <= !mask` and `max > 0`
    pub fn new(net: u32, mask: u32, max: usize) -> Self {
        debug_assert!(max > 0, "max must be non-zero");
        debug_assert!(
            (max as u64) <= ((!mask) as u64),
            "max exceeds addressable range"
        );
        let capacity = NonZeroUsize::new(max).expect("max must be > 0");
        Self {
            lru: LruCache::new(capacity),
            rev: HashMap::new(),
            records: vec![None; max],
            net,
            mask,
            max,
            next_free: 0,
        }
    }

    /// Look up or insert a name, returning the mapped IP address (`net | idx`).
    ///
    /// Touching an existing entry refreshes its LRU position.
    /// Inserting into a full cache evicts the least-recently-used entry.
    ///
    /// Returns `None` only if `max == 0` (degenerate cache).
    pub fn find(&mut self, name: &str) -> Option<u32> {
        if self.max == 0 {
            return None;
        }

        // Cache hit — lru.get() touches the entry (moves to MRU).
        if let Some(&idx) = self.lru.get(name) {
            return Some(self.net | idx as u32);
        }

        // Cache miss — allocate or evict a slot.
        let idx = if self.next_free < self.max {
            let idx = self.next_free;
            self.next_free += 1;
            idx
        } else {
            // Evict the least-recently-used entry and reuse its slot.
            let (evicted_name, evicted_idx) = self.lru.pop_lru()?;
            let evicted_ip = self.net | evicted_idx as u32;
            self.rev.remove(&evicted_ip);
            self.records[evicted_idx] = None;
            // Suppress unused-variable warning in release builds.
            let _ = evicted_name;
            evicted_idx
        };

        let ip = self.net | idx as u32;
        self.lru.put(name.to_string(), idx);
        self.records[idx] = Some(name.to_string());
        self.rev.insert(ip, name.to_string());
        Some(ip)
    }

    /// Reverse lookup: IP → name.
    ///
    /// Returns `None` if the IP is not currently mapped (never inserted or evicted).
    /// Touching a valid entry refreshes its LRU position (mirrors C behaviour).
    pub fn lookup(&mut self, ip: u32) -> Option<&str> {
        // Guard: IP must belong to this cache's network range.
        if ip & self.mask != self.net {
            return None;
        }

        // Clone the name so we can call lru.get() (which requires &mut self.lru)
        // without holding a live borrow into self.rev.
        let name = self.rev.get(&ip)?.clone();
        // Touch LRU — mirrors C hev_mapped_dns_lookup which moves entry to MRU tail.
        self.lru.get(&name);
        self.rev.get(&ip).map(|s| s.as_str())
    }

    /// Process a DNS request and produce a response with mapped A-record answers.
    ///
    /// `req` – mutable request buffer; label-length bytes are overwritten in-place
    ///         with `'.'` (mirrors `hev_mapped_dns_handle` which mutates the request)
    /// `res` – response output buffer; must be `>= req.len()` bytes
    ///
    /// Returns the length of the response on success.
    pub fn handle(&mut self, req: &mut [u8], res: &mut [u8]) -> Result<usize, DnsCacheError> {
        let req_len = req.len();
        if req_len < 12 {
            return Err(DnsCacheError::Truncated);
        }
        if res.len() < req_len {
            return Err(DnsCacheError::BufferTooSmall);
        }

        // Copy request into response buffer (we'll mutate the copy's header fields).
        res[..req_len].copy_from_slice(req);

        // QDCOUNT from header bytes [4..6].
        let qcount = u16::from_be_bytes([req[4], req[5]]) as usize;
        if qcount > 32 {
            return Err(DnsCacheError::TooManyQuestions);
        }

        // Walk questions starting at offset 12.
        let mut off = 12usize;
        let mut ips = [0u32; 32];
        let mut ipn = 0usize;
        // Each question's name starts at offset 12 (the first question begins right after header).
        let question_name_start = 12usize;

        for _q in 0..qcount {
            // Record start of this question's name for the answer name pointer.
            // The C code uses poff = off at the start of each question iteration,
            // but all answer records point to the first question name (offset 12).
            // We mirror C exactly: poff is set once and reused for all answer records.

            // Parse labels: replace each length byte with '.' in req (mirroring C mutation).
            loop {
                if off >= req_len {
                    return Err(DnsCacheError::Truncated);
                }
                let label_len = req[off] as usize;
                if label_len == 0 {
                    off += 1; // skip null terminator
                    break;
                }
                // Replace length byte with '.' in the request buffer (C behaviour).
                req[off] = b'.';
                off += 1 + label_len;
                if off > req_len {
                    return Err(DnsCacheError::Truncated);
                }
            }

            // Need QTYPE (2B) + QCLASS (2B).
            if off + 4 > req_len {
                return Err(DnsCacheError::Truncated);
            }
            let qtype = u16::from_be_bytes([req[off], req[off + 1]]);
            let qclass = u16::from_be_bytes([req[off + 2], req[off + 3]]);
            off += 4;

            // Only handle A records in IN class.
            if qtype == 1 && qclass == 1 {
                // Extract the domain name from the (mutated) request buffer.
                // After label-length replacement, the name region in req[12..] looks like:
                // ".<label>.<label>\0" — skip leading '.' and trailing '\0'.
                // We reconstruct the FQDN from the original res[] copy (not mutated yet).
                let name = Self::extract_name(res, question_name_start);
                if let Some(ip) = self.find(&name) {
                    if ipn < 32 {
                        ips[ipn] = ip;
                        ipn += 1;
                    }
                }
            }
        }

        // Build answer records at current offset in res[].
        let mut woff = off;
        let mut answers_written = 0usize;
        for &ip in ips[..ipn].iter() {
            if woff + 16 > res.len() {
                break;
            }
            // Name pointer: 0xC0 <offset of question name start>
            res[woff] = 0xc0;
            res[woff + 1] = question_name_start as u8;
            // TYPE = A (1)
            res[woff + 2] = 0;
            res[woff + 3] = 1;
            // CLASS = IN (1)
            res[woff + 4] = 0;
            res[woff + 5] = 1;
            // TTL = 1
            res[woff + 6] = 0;
            res[woff + 7] = 0;
            res[woff + 8] = 0;
            res[woff + 9] = 1;
            // RDLENGTH = 4
            res[woff + 10] = 0;
            res[woff + 11] = 4;
            // RDATA = IP in big-endian
            let ip_bytes = ip.to_be_bytes();
            res[woff + 12] = ip_bytes[0];
            res[woff + 13] = ip_bytes[1];
            res[woff + 14] = ip_bytes[2];
            res[woff + 15] = ip_bytes[3];
            woff += 16;
            answers_written += 1;
        }

        // Patch response flags: QR=1, RA = (RD >> 1).
        // C: fl |= 0x8000 | ((fl & 0x0100) >> 1)
        let fl = u16::from_be_bytes([res[2], res[3]]);
        let fl = fl | 0x8000 | ((fl & 0x0100) >> 1);
        let fl_bytes = fl.to_be_bytes();
        res[2] = fl_bytes[0];
        res[3] = fl_bytes[1];

        // Patch ANCOUNT.
        let an_bytes = (answers_written as u16).to_be_bytes();
        res[6] = an_bytes[0];
        res[7] = an_bytes[1];

        Ok(woff)
    }

    /// Extract a domain name from a DNS wire-format question starting at `start` in `buf`.
    ///
    /// Labels are separated by length bytes. Returns the FQDN without leading or trailing dots.
    fn extract_name(buf: &[u8], start: usize) -> String {
        let mut name = String::new();
        let mut off = start;
        let mut first = true;
        loop {
            if off >= buf.len() {
                break;
            }
            let label_len = buf[off] as usize;
            if label_len == 0 {
                break;
            }
            if !first {
                name.push('.');
            }
            off += 1;
            if off + label_len > buf.len() {
                break;
            }
            if let Ok(s) = std::str::from_utf8(&buf[off..off + label_len]) {
                name.push_str(s);
            }
            off += label_len;
            first = false;
        }
        name
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    /// Network: 10.0.0.0/24 — supports up to 255 entries.
    const NET: u32 = 0x0a_00_00_00;
    const MASK: u32 = 0xff_ff_ff_00;

    /// Encode `name` in DNS wire-format labels: `[len]label...[\0]`.
    fn encode_name(name: &str) -> Vec<u8> {
        let mut buf = Vec::new();
        for label in name.split('.') {
            assert!(label.len() <= 63, "label too long");
            buf.push(label.len() as u8);
            buf.extend_from_slice(label.as_bytes());
        }
        buf.push(0u8);
        buf
    }

    /// Build a minimal DNS A query packet for `name` with RD=1.
    fn make_a_query(name: &str) -> Vec<u8> {
        let mut pkt = Vec::with_capacity(64);
        pkt.extend_from_slice(&1u16.to_be_bytes()); // ID = 1
        pkt.extend_from_slice(&0x0100u16.to_be_bytes()); // flags: RD=1
        pkt.extend_from_slice(&1u16.to_be_bytes()); // QDCOUNT = 1
        pkt.extend_from_slice(&0u16.to_be_bytes()); // ANCOUNT = 0
        pkt.extend_from_slice(&0u16.to_be_bytes()); // NSCOUNT = 0
        pkt.extend_from_slice(&0u16.to_be_bytes()); // ARCOUNT = 0
        pkt.extend(encode_name(name));
        pkt.extend_from_slice(&1u16.to_be_bytes()); // QTYPE = A
        pkt.extend_from_slice(&1u16.to_be_bytes()); // QCLASS = IN
        pkt
    }

    fn make_multi_a_query(names: &[&str]) -> Vec<u8> {
        let mut pkt = Vec::with_capacity(128);
        pkt.extend_from_slice(&1u16.to_be_bytes()); // ID = 1
        pkt.extend_from_slice(&0x0100u16.to_be_bytes()); // flags: RD=1
        pkt.extend_from_slice(&(names.len() as u16).to_be_bytes());
        pkt.extend_from_slice(&0u16.to_be_bytes()); // ANCOUNT = 0
        pkt.extend_from_slice(&0u16.to_be_bytes()); // NSCOUNT = 0
        pkt.extend_from_slice(&0u16.to_be_bytes()); // ARCOUNT = 0
        for name in names {
            pkt.extend(encode_name(name));
            pkt.extend_from_slice(&1u16.to_be_bytes()); // QTYPE = A
            pkt.extend_from_slice(&1u16.to_be_bytes()); // QCLASS = IN
        }
        pkt
    }

    // -----------------------------------------------------------------------
    // Test 1: sequential IP assignment
    // -----------------------------------------------------------------------
    #[test]
    fn test_sequential_ip_assignment() {
        let mut cache = DnsCache::new(NET, MASK, 4);
        assert_eq!(cache.find("a.com"), Some(NET));
        assert_eq!(cache.find("b.com"), Some(NET | 1));
        assert_eq!(cache.find("c.com"), Some(NET | 2));
        assert_eq!(cache.find("d.com"), Some(NET | 3));
    }

    // -----------------------------------------------------------------------
    // Test 2: LRU eviction removes oldest entry and reuses its slot
    // -----------------------------------------------------------------------
    #[test]
    fn test_eviction_oldest_removed() {
        let mut cache = DnsCache::new(NET, MASK, 4);
        cache.find("a.com"); // slot 0 (oldest)
        cache.find("b.com"); // slot 1
        cache.find("c.com"); // slot 2
        cache.find("d.com"); // slot 3 — cache now full

        // Inserting "e.com" must evict "a.com" (LRU) and reuse slot 0.
        let ip_e = cache.find("e.com");
        assert_eq!(ip_e, Some(NET), "e.com must reclaim slot 0");

        // Entries inserted after "a.com" must survive.
        assert_eq!(cache.find("b.com"), Some(NET | 1));
        assert_eq!(cache.find("c.com"), Some(NET | 2));
        assert_eq!(cache.find("d.com"), Some(NET | 3));
    }

    // -----------------------------------------------------------------------
    // Test 3: LRU touch (re-find) protects an entry from eviction
    // -----------------------------------------------------------------------
    #[test]
    fn test_lru_touch_protects_from_eviction() {
        // Fill cache: LRU order after inserts = [a(oldest), b, c(newest)].
        let mut cache = DnsCache::new(NET, MASK, 3);
        cache.find("a.com"); // slot 0
        cache.find("b.com"); // slot 1
        cache.find("c.com"); // slot 2

        // Touch "a.com" → moves to MRU tail; LRU order = [b, c, a].
        let ip_a_again = cache.find("a.com");
        assert_eq!(ip_a_again, Some(NET), "a.com must keep its IP on re-find");

        // Insert "d.com" → must evict "b.com" (new LRU front), reuse slot 1.
        let ip_d = cache.find("d.com");
        assert_eq!(
            ip_d,
            Some(NET | 1),
            "d.com must reuse slot 1 (b.com's slot)"
        );

        // "a.com" and "c.com" must still be reachable.
        assert_eq!(cache.find("a.com"), Some(NET));
        assert_eq!(cache.find("c.com"), Some(NET | 2));
    }

    // -----------------------------------------------------------------------
    // Test 4: handle() with a valid A query returns the correct mapped IP
    // -----------------------------------------------------------------------
    #[test]
    fn test_handle_valid_a_query_returns_mapped_ip() {
        let mut cache = DnsCache::new(NET, MASK, 4);
        let mut req = make_a_query("example.com");
        let mut res = vec![0u8; 512];

        let rlen = cache
            .handle(&mut req, &mut res)
            .expect("handle must succeed");

        // Response must include at least one answer record (16 bytes) beyond the request.
        assert!(
            rlen > req.len(),
            "response len {rlen} must exceed query len {}",
            req.len()
        );

        // ANCOUNT (bytes [6..8]) must be 1.
        let ancount = u16::from_be_bytes([res[6], res[7]]);
        assert_eq!(ancount, 1, "ANCOUNT must be 1");

        // Answer section starts immediately after the question.
        // Header = 12 bytes.
        // QNAME for "example.com" = [7]example[3]com[0] = 7+1+3+1+1 = 13 bytes.
        // QTYPE + QCLASS = 4 bytes.  Total question = 17 bytes.
        let ans_off: usize = 12 + 13 + 4; // = 29

        // Name pointer: 0xc0 <question_start_offset>
        assert_eq!(res[ans_off], 0xc0, "name compression pointer high byte");
        assert_eq!(
            res[ans_off + 1],
            12,
            "name pointer must reference offset 12"
        );
        // TYPE = A (1)
        assert_eq!(
            u16::from_be_bytes([res[ans_off + 2], res[ans_off + 3]]),
            1,
            "answer TYPE must be A (1)"
        );
        // CLASS = IN (1)
        assert_eq!(
            u16::from_be_bytes([res[ans_off + 4], res[ans_off + 5]]),
            1,
            "answer CLASS must be IN (1)"
        );
        // TTL = 1
        assert_eq!(
            u32::from_be_bytes([
                res[ans_off + 6],
                res[ans_off + 7],
                res[ans_off + 8],
                res[ans_off + 9]
            ]),
            1,
            "TTL must be 1"
        );
        // RDLENGTH = 4
        assert_eq!(
            u16::from_be_bytes([res[ans_off + 10], res[ans_off + 11]]),
            4,
            "RDLENGTH must be 4"
        );
        // RDATA = NET | 0 = 10.0.0.0
        let ip = u32::from_be_bytes([
            res[ans_off + 12],
            res[ans_off + 13],
            res[ans_off + 14],
            res[ans_off + 15],
        ]);
        assert_eq!(ip, NET, "RDATA must be NET | 0");

        // Total response length
        assert_eq!(
            rlen,
            ans_off + 16,
            "response length must be {}",
            ans_off + 16
        );
    }

    #[test]
    fn test_handle_limits_answer_count_to_serialized_answers() {
        let mut cache = DnsCache::new(NET, MASK, 4);
        let mut req = make_multi_a_query(&["a.com", "b.com"]);
        let mut res = vec![0u8; req.len() + 16];

        let rlen = cache
            .handle(&mut req, &mut res)
            .expect("handle must succeed when only one answer fits");

        let ancount = u16::from_be_bytes([res[6], res[7]]);
        assert_eq!(
            ancount, 1,
            "ANCOUNT must match the number of written answers"
        );
        assert_eq!(
            rlen,
            req.len() + 16,
            "response length must reflect exactly one serialized answer"
        );
    }

    // -----------------------------------------------------------------------
    // Test 5: handle() rejects query with more than 32 questions
    // -----------------------------------------------------------------------
    #[test]
    fn test_handle_too_many_questions_returns_err() {
        let mut cache = DnsCache::new(NET, MASK, 4);

        // Raw 12-byte header with QDCOUNT=33 (big-endian).
        let mut req = [0u8; 12];
        req[5] = 33; // QDCOUNT = 0x0021 = 33
        let mut res = [0u8; 128];

        let err = cache.handle(&mut req, &mut res).unwrap_err();
        assert!(
            matches!(err, DnsCacheError::TooManyQuestions),
            "expected TooManyQuestions, got {err:?}"
        );
    }

    // -----------------------------------------------------------------------
    // Test 6: handle() rejects truncated / malformed packets
    // -----------------------------------------------------------------------
    #[test]
    fn test_handle_truncated_packet_returns_err() {
        let mut cache = DnsCache::new(NET, MASK, 4);

        // 12-byte header with QDCOUNT=1 but no question bytes → Truncated.
        let mut req = [0u8; 12];
        req[5] = 1; // QDCOUNT = 1
        let mut res = [0u8; 128];
        let err = cache.handle(&mut req, &mut res).unwrap_err();
        assert!(
            matches!(err, DnsCacheError::Truncated),
            "expected Truncated for missing question data, got {err:?}"
        );

        // A valid packet must succeed (proves the stub is wrong for positive cases).
        let mut req2 = make_a_query("ok.com");
        let mut res2 = vec![0u8; 512];
        assert!(
            cache.handle(&mut req2, &mut res2).is_ok(),
            "valid A query must not return Err"
        );
    }

    // -----------------------------------------------------------------------
    // Test 7: lookup(ip) after find(name) returns the same name
    // -----------------------------------------------------------------------
    #[test]
    fn test_lookup_by_ip_after_find() {
        let mut cache = DnsCache::new(NET, MASK, 4);
        let ip = cache.find("foo.example.com").expect("find must succeed");
        let name = cache.lookup(ip).expect("lookup must return a name");
        assert_eq!(name, "foo.example.com");
    }

    // -----------------------------------------------------------------------
    // Test 8: evicted slot is reused for the new entry
    // -----------------------------------------------------------------------
    #[test]
    fn test_evicted_slot_reused_for_new_entry() {
        let mut cache = DnsCache::new(NET, MASK, 2);
        cache.find("a.com"); // slot 0
        cache.find("b.com"); // slot 1 — full

        // Inserting "c.com" must evict "a.com" and reuse slot 0.
        let ip_c = cache.find("c.com").expect("find must succeed");
        assert_eq!(ip_c, NET, "c.com must get the evicted slot 0");

        // "b.com" at slot 1 must be unaffected.
        assert_eq!(cache.find("b.com"), Some(NET | 1));
        // "c.com" is idempotent: second find returns same IP.
        assert_eq!(cache.find("c.com"), Some(NET));
        // Reverse lookup for "c.com"'s IP must work too.
        assert_eq!(cache.lookup(NET), Some("c.com"));
    }

    // -----------------------------------------------------------------------
    // Test 9: DNS response flags — QR=1, RD mirrored as RA
    // -----------------------------------------------------------------------
    #[test]
    fn test_response_flags_qr_and_ra_set() {
        let mut cache = DnsCache::new(NET, MASK, 4);
        // Query flags = 0x0100 (RD=1).
        let mut req = make_a_query("flags.test");
        let mut res = vec![0u8; 512];

        cache
            .handle(&mut req, &mut res)
            .expect("handle must succeed");

        let flags = u16::from_be_bytes([res[2], res[3]]);
        // QR=1 (bit 15): response marker
        assert_eq!(flags & 0x8000, 0x8000, "QR bit must be set in response");
        // RD=1 (bit 8): preserved from query
        assert_eq!(
            flags & 0x0100,
            0x0100,
            "RD bit must be preserved from query"
        );
        // RA=1 (bit 7): C code sets RA = (query_RD >> 1); since RD=1, RA=1
        assert_eq!(
            flags & 0x0080,
            0x0080,
            "RA bit must be set when query had RD=1"
        );
    }
}

// ---------------------------------------------------------------------------
// Property-based tests (proptest)
// ---------------------------------------------------------------------------
#[cfg(test)]
mod proptest_tests {
    use super::*;
    use proptest::prelude::*;

    const NET: u32 = 0x0a_00_00_00;
    const MASK: u32 = 0xff_ff_ff_00;
    const MAX: usize = 8;

    /// Generate a valid label token (1-8 alphanumeric bytes).
    fn arb_label() -> impl Strategy<Value = String> {
        prop::string::string_regex("[a-z]{1,8}").unwrap()
    }

    /// Generate a valid FQDN: 1-3 labels joined with '.'.
    fn arb_name() -> impl Strategy<Value = String> {
        prop::collection::vec(arb_label(), 1usize..=3).prop_map(|labels| labels.join("."))
    }

    /// Generate a sequence of 1-30 names (duplicates allowed for LRU-touch
    /// semantics; distinct pool is bounded so cache saturation is exercised).
    fn arb_names(max_ops: usize, pool: usize) -> impl Strategy<Value = Vec<String>> {
        prop::collection::vec(arb_label(), 1usize..=pool).prop_flat_map(move |pool_names| {
            let pool_names = std::sync::Arc::new(pool_names);
            prop::collection::vec(
                (0usize..pool_names.len()).prop_map(move |i| pool_names[i].clone()),
                1..=max_ops,
            )
        })
    }

    // -----------------------------------------------------------------------
    // Property 1: Reverse-lookup consistency
    //
    // Invariant: immediately after find(name) returns Some(ip),
    // lookup(ip) must return Some(name).
    // -----------------------------------------------------------------------
    proptest! {
        #![proptest_config(ProptestConfig::with_cases(5_000))]

        #[test]
        fn prop_reverse_lookup_consistent_after_find(
            names in arb_names(40, MAX + 4),
        ) {
            let mut cache = DnsCache::new(NET, MASK, MAX);
            for name in &names {
                if let Some(ip) = cache.find(name) {
                    let looked_up = cache.lookup(ip);
                    prop_assert_eq!(
                        looked_up,
                        Some(name.as_str()),
                        "reverse lookup failed: name={} ip={:#010x}",
                        name,
                        ip
                    );
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Property 2: No stale reverse entries after eviction
    //
    // After inserting MAX+1 distinct names, the first name inserted must no
    // longer be present as the value at its original IP (the slot was reused).
    // -----------------------------------------------------------------------
    proptest! {
        #![proptest_config(ProptestConfig::with_cases(2_000))]

        #[test]
        fn prop_evicted_name_no_longer_at_old_ip(
            names in prop::collection::vec(arb_name(), (MAX + 1)..=(MAX + 1)),
        ) {
            // Only proceed if all MAX+1 names are distinct.
            let distinct: std::collections::HashSet<_> = names.iter().collect();
            prop_assume!(distinct.len() == MAX + 1);

            let mut cache = DnsCache::new(NET, MASK, MAX);

            // Insert first MAX names; record the first name's IP.
            let mut first_ip = 0u32;
            for (i, name) in names[..MAX].iter().enumerate() {
                let ip = cache.find(name).expect("find must succeed");
                if i == 0 {
                    first_ip = ip;
                }
            }

            // Insert the (MAX+1)th name, which must evict name[0] (LRU).
            cache.find(&names[MAX]).expect("find must succeed");

            // name[0] must no longer be the value at its old IP.
            let at_old_slot = cache.lookup(first_ip);
            prop_assert!(
                at_old_slot != Some(names[0].as_str()),
                "evicted name '{}' still present at old IP {:#010x}",
                names[0],
                first_ip,
            );
        }
    }

    // -----------------------------------------------------------------------
    // Property 3: LRU touch prevents eviction
    //
    // If name[0] is accessed again before name[1], then when the cache is
    // full and a new name arrives, name[1] (the new LRU) is evicted instead
    // of name[0].
    // -----------------------------------------------------------------------
    proptest! {
        #![proptest_config(ProptestConfig::with_cases(2_000))]

        #[test]
        fn prop_lru_touch_protects_entry(
            names in prop::collection::vec(arb_name(), (MAX + 1)..=(MAX + 1)),
        ) {
            // Need at least 3 distinct names.
            let distinct: std::collections::HashSet<_> = names.iter().collect();
            prop_assume!(distinct.len() == MAX + 1);

            let mut cache = DnsCache::new(NET, MASK, MAX);

            // Insert MAX names in order: name[0] is LRU, name[MAX-1] is MRU.
            for name in &names[..MAX] {
                cache.find(name).expect("find must succeed");
            }

            // Touch name[0] — promotes it to MRU.  name[1] becomes LRU.
            let ip0 = cache.find(&names[0]).expect("re-find must succeed");

            // Insert the (MAX+1)th distinct name → evicts name[1] (new LRU).
            cache.find(&names[MAX]).expect("find must succeed");

            // name[0] must still be accessible at ip0.
            let looked_up = cache.lookup(ip0);
            prop_assert_eq!(
                looked_up,
                Some(names[0].as_str()),
                "name[0] '{}' must survive eviction after LRU touch",
                names[0],
            );
        }
    }

    // -----------------------------------------------------------------------
    // Property 4: handle() never panics; either returns Ok or a known Err.
    // -----------------------------------------------------------------------
    proptest! {
        #![proptest_config(ProptestConfig::with_cases(5_000))]

        #[test]
        fn prop_handle_never_panics(
            mut req in prop::collection::vec(any::<u8>(), 0usize..=256),
        ) {
            let mut cache = DnsCache::new(NET, MASK, MAX);
            let mut res = vec![0u8; 512];
            // Must not panic; return value may be Ok or any Err variant.
            let _ = cache.handle(&mut req, &mut res);
        }
    }
}
