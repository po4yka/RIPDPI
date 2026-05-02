pub(crate) const FNV_OFFSET: u64 = 0xcbf29ce484222325;

const FNV_PRIME: u64 = 0x100000001b3;

pub fn stable_hash_combine(lhs: u64, rhs: u64) -> u64 {
    let mut hash = FNV_OFFSET;
    stable_hash_update(&mut hash, lhs.to_string().as_bytes());
    stable_hash_update(&mut hash, b"|");
    stable_hash_update(&mut hash, rhs.to_string().as_bytes());
    hash
}

pub(crate) fn stable_hash_update(hash: &mut u64, bytes: &[u8]) {
    for byte in bytes {
        *hash ^= u64::from(*byte);
        *hash = hash.wrapping_mul(FNV_PRIME);
    }
}
