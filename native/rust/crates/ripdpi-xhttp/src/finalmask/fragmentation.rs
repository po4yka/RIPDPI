use rand::RngExt;

pub(crate) fn fragment_bytes(payload: &[u8], packets: usize, min_bytes: usize, max_bytes: usize) -> Vec<Vec<u8>> {
    if payload.is_empty() || packets <= 1 {
        return vec![payload.to_vec()];
    }
    let mut remaining = payload.len();
    if remaining < packets.saturating_mul(min_bytes) || remaining > packets.saturating_mul(max_bytes) {
        return vec![payload.to_vec()];
    }

    let mut rng = rand::rng();
    let mut cursor = 0usize;
    let mut frames = Vec::with_capacity(packets);
    for index in 0..packets {
        let fragments_left = packets - index;
        if fragments_left == 1 {
            frames.push(payload[cursor..].to_vec());
            break;
        }
        let min_for_rest = (fragments_left - 1) * min_bytes;
        let max_for_rest = (fragments_left - 1) * max_bytes;
        let lower = min_bytes.max(remaining.saturating_sub(max_for_rest));
        let upper = max_bytes.min(remaining.saturating_sub(min_for_rest));
        if lower > upper {
            return vec![payload.to_vec()];
        }
        let current = if lower == upper { lower } else { rng.random_range(lower..=upper) };
        frames.push(payload[cursor..cursor + current].to_vec());
        cursor += current;
        remaining -= current;
    }
    frames
}
