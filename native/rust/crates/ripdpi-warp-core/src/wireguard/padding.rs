use boringtun::noise::TransportPadding;

/// WireGuard buckets are bounded by the configured inner MTU.
pub(super) struct PlainWireGuardPadding(pub(super) usize);

impl TransportPadding for PlainWireGuardPadding {
    fn padded_len(&self, plaintext_len: usize) -> usize {
        (plaintext_len.saturating_add(15) & !15).min(self.0)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn protocol_buckets_stop_at_the_inner_mtu() {
        let policy = PlainWireGuardPadding(1330);
        for (length, expected) in
            [(0, 0), (20, 32), (31, 32), (32, 32), (33, 48), (1328, 1328), (1329, 1330), (1330, 1330)]
        {
            assert_eq!(policy.padded_len(length), expected, "inner length {length}");
        }
    }
}
