use std::io;
use std::sync::{Arc, OnceLock};

use rand::RngExt;
use sha2::{Digest, Sha256};

use super::go_rand::GoRand;

/// Uplink table of Xray's sudoku finalmask with the default entropy layout
/// and no custom table (`transport/internet/finalmask/sudoku/table.go`).
#[derive(Clone)]
pub(crate) struct SudokuTable {
    encode: Vec<Vec<[u8; 4]>>,
}

impl SudokuTable {
    pub(crate) fn new(password: &str) -> io::Result<Self> {
        let patterns = base_patterns();
        if patterns.len() < 256 {
            return Err(io::Error::other("sudoku pattern table is incomplete"));
        }

        let mut order = (0..patterns.len()).collect::<Vec<_>>();
        let hash = Sha256::digest(password.as_bytes());
        let mut seed_bytes = [0u8; 8];
        seed_bytes.copy_from_slice(&hash[..8]);
        // Xray seeds Go math/rand with the first 8 bytes of SHA-256(password).
        GoRand::new(i64::from_be_bytes(seed_bytes)).shuffle(&mut order);

        let mut encode = vec![Vec::new(); 256];
        let mut decode_map = std::collections::BTreeMap::new();
        for value in 0u16..=255 {
            let pattern_set = &patterns[order[usize::from(value)]];
            if pattern_set.is_empty() {
                return Err(io::Error::other("sudoku byte pattern set is empty"));
            }
            let mut encodings = Vec::with_capacity(pattern_set.len());
            for groups in pattern_set {
                let hints = [
                    encode_group(groups[0]),
                    encode_group(groups[1]),
                    encode_group(groups[2]),
                    encode_group(groups[3]),
                ];
                let key = pack_key(sort4(hints));
                if let Some(existing) = decode_map.insert(key, value as u8)
                    && existing != value as u8
                {
                    return Err(io::Error::other("sudoku decode collision"));
                }
                encodings.push(hints);
            }
            encode[usize::from(value)] = encodings;
        }

        Ok(Self { encode })
    }
}

pub(crate) struct SudokuEncoder {
    table: Arc<SudokuTable>,
}

impl SudokuEncoder {
    pub(crate) fn new(table: Arc<SudokuTable>) -> Self {
        Self { table }
    }

    pub(crate) fn encode_chunk(&mut self, payload: &[u8]) -> io::Result<Vec<u8>> {
        let mut rng = rand::rng();
        let mut out = Vec::with_capacity(payload.len() * 4);
        for &byte in payload {
            let encodings = &self.table.encode[usize::from(byte)];
            if encodings.is_empty() {
                return Err(io::Error::other("missing sudoku encoding"));
            }
            let hints = encodings[rng.random_range(0..encodings.len())];
            let permutation = permutation4(rng.random_range(0..24));
            for index in permutation {
                out.push(hints[index]);
            }
        }
        Ok(out)
    }
}

/// Downlink decoder. An Xray server writes the downlink "packed": each hint
/// byte carries 6 data bits in the entropy layout, and the pad marker `0x80`
/// ends a write whose bits do not fill a byte (`conn_tcp_packed.go`). The
/// password table is not used in this direction.
#[derive(Default)]
pub(crate) struct SudokuDecoder {
    bits: u32,
    bit_count: u32,
}

impl SudokuDecoder {
    pub(crate) fn decode_stream_chunk(&mut self, payload: &[u8]) -> Vec<u8> {
        const PAD_MARKER: u8 = 0x80;
        let mut out = Vec::with_capacity(payload.len() * 3 / 4 + 1);
        for &byte in payload {
            if !is_entropy_hint(byte) {
                if byte == PAD_MARKER {
                    self.bits = 0;
                    self.bit_count = 0;
                }
                continue;
            }
            self.bits = (self.bits << 6) | u32::from(((byte >> 1) & 0x30) | (byte & 0x0f));
            self.bit_count += 6;
            if self.bit_count >= 8 {
                self.bit_count -= 8;
                out.push((self.bits >> self.bit_count) as u8);
                self.bits &= (1 << self.bit_count) - 1;
            }
        }
        out
    }
}

fn encode_group(group: u8) -> u8 {
    let value = group & 0x3f;
    ((value & 0x30) << 1) | (value & 0x0f)
}

fn is_entropy_hint(byte: u8) -> bool {
    (byte & 0x90) == 0
}

fn permutation4(index: usize) -> [usize; 4] {
    const PERMUTATIONS: [[usize; 4]; 24] = [
        [0, 1, 2, 3],
        [0, 1, 3, 2],
        [0, 2, 1, 3],
        [0, 2, 3, 1],
        [0, 3, 1, 2],
        [0, 3, 2, 1],
        [1, 0, 2, 3],
        [1, 0, 3, 2],
        [1, 2, 0, 3],
        [1, 2, 3, 0],
        [1, 3, 0, 2],
        [1, 3, 2, 0],
        [2, 0, 1, 3],
        [2, 0, 3, 1],
        [2, 1, 0, 3],
        [2, 1, 3, 0],
        [2, 3, 0, 1],
        [2, 3, 1, 0],
        [3, 0, 1, 2],
        [3, 0, 2, 1],
        [3, 1, 0, 2],
        [3, 1, 2, 0],
        [3, 2, 0, 1],
        [3, 2, 1, 0],
    ];
    PERMUTATIONS[index % PERMUTATIONS.len()]
}

fn base_patterns() -> &'static Vec<Vec<[u8; 4]>> {
    static BASE_PATTERNS: OnceLock<Vec<Vec<[u8; 4]>>> = OnceLock::new();
    BASE_PATTERNS.get_or_init(build_base_patterns)
}

fn build_base_patterns() -> Vec<Vec<[u8; 4]>> {
    let grids = generate_all_grids();
    let positions = hint_positions();
    let mut patterns = vec![Vec::new(); grids.len()];

    for positions_group in positions {
        let mut counts = std::collections::HashMap::with_capacity(grids.len());
        let mut keys = Vec::with_capacity(grids.len());
        let mut groups_by_grid = Vec::with_capacity(grids.len());
        for grid in &grids {
            let groups = sort4([
                clue_group(*grid, positions_group[0]),
                clue_group(*grid, positions_group[1]),
                clue_group(*grid, positions_group[2]),
                clue_group(*grid, positions_group[3]),
            ]);
            let key = pack_key(groups);
            *counts.entry(key).or_insert(0u16) += 1;
            keys.push(key);
            groups_by_grid.push(groups);
        }
        for (index, key) in keys.into_iter().enumerate() {
            if counts.get(&key) == Some(&1) {
                patterns[index].push(groups_by_grid[index]);
            }
        }
    }

    patterns
}

type SudokuGrid = [u8; 16];

fn generate_all_grids() -> Vec<SudokuGrid> {
    fn dfs(index: usize, grid: &mut SudokuGrid, out: &mut Vec<SudokuGrid>) {
        if index == 16 {
            out.push(*grid);
            return;
        }
        let row = index / 4;
        let col = index % 4;
        let box_row = (row / 2) * 2;
        let box_col = (col / 2) * 2;

        for number in 1u8..=4 {
            let row_ok = (0..4).all(|position| grid[row * 4 + position] != number);
            let col_ok = (0..4).all(|position| grid[position * 4 + col] != number);
            let box_ok = (0..2).all(|row_offset| {
                (0..2).all(|col_offset| grid[(box_row + row_offset) * 4 + box_col + col_offset] != number)
            });
            if !(row_ok && col_ok && box_ok) {
                continue;
            }
            grid[index] = number;
            dfs(index + 1, grid, out);
            grid[index] = 0;
        }
    }

    let mut out = Vec::with_capacity(288);
    let mut grid = [0u8; 16];
    dfs(0, &mut grid, &mut out);
    out
}

fn hint_positions() -> Vec<[u8; 4]> {
    let mut positions = Vec::with_capacity(1820);
    for a in 0..13 {
        for b in a + 1..14 {
            for c in b + 1..15 {
                for d in c + 1..16 {
                    positions.push([a as u8, b as u8, c as u8, d as u8]);
                }
            }
        }
    }
    positions
}

fn clue_group(grid: SudokuGrid, position: u8) -> u8 {
    ((grid[usize::from(position)] - 1) << 4) | (position & 0x0f)
}

fn sort4(mut values: [u8; 4]) -> [u8; 4] {
    if values[0] > values[1] {
        values.swap(0, 1);
    }
    if values[2] > values[3] {
        values.swap(2, 3);
    }
    if values[0] > values[2] {
        values.swap(0, 2);
    }
    if values[1] > values[3] {
        values.swap(1, 3);
    }
    if values[1] > values[2] {
        values.swap(1, 2);
    }
    values
}

fn pack_key(values: [u8; 4]) -> u32 {
    u32::from(values[0]) << 24 | u32::from(values[1]) << 16 | u32::from(values[2]) << 8 | u32::from(values[3])
}

#[cfg(test)]
mod tests {
    use super::*;

    /// SHA-256 over every byte's `u16` big-endian encoding count followed by
    /// its hint tuples, in table order. The expected values come from Xray-core
    /// `transport/internet/finalmask/sudoku/table.go` `getTables` (Xray-core
    /// 1b1647c, Go 1.27), serialized the same way.
    fn encode_table_digest(table: &SudokuTable) -> String {
        let mut hasher = Sha256::new();
        for encodings in &table.encode {
            hasher.update(u16::try_from(encodings.len()).expect("encoding count fits u16").to_be_bytes());
            for hints in encodings {
                hasher.update(hints);
            }
        }
        hex::encode(hasher.finalize())
    }

    #[test]
    fn table_matches_xray_for_the_same_password() {
        let table = SudokuTable::new("fixture-seed").expect("table");
        assert_eq!(table.encode[0][0], [0x00, 0x2f, 0x42, 0x6d]);
        assert_eq!(table.encode[1][0], [0x20, 0x4b, 0x62, 0x64]);
        assert_eq!(table.encode[255][0], [0x09, 0x23, 0x4a, 0x60]);
        assert_eq!(encode_table_digest(&table), "eea11e01632ee2111a0005a120bef1edda1b2afdd58667b1504518bfff6d2dd1");

        let empty = SudokuTable::new("").expect("table");
        assert_eq!(encode_table_digest(&empty), "bf89940defc58da3feb19fcc3eaec20ac80d78d367f796f7d33d7a298210ff7e");
    }

    /// Xray's server reads the uplink with `decodeBytes`: it keeps only hint
    /// bytes and maps each sorted 4-tuple through its own decode table.
    #[test]
    fn uplink_decodes_with_xray_server_rule() {
        let table = Arc::new(SudokuTable::new("fixture-seed").expect("table"));
        let mut server_decode = std::collections::HashMap::new();
        for (value, encodings) in table.encode.iter().enumerate() {
            for hints in encodings {
                server_decode.insert(pack_key(sort4(*hints)), u8::try_from(value).expect("byte value"));
            }
        }

        let encoded = SudokuEncoder::new(Arc::clone(&table)).encode_chunk(b"hello world").expect("encode");

        let decoded: Vec<u8> = encoded
            .chunks(4)
            .map(|hints| server_decode[&pack_key(sort4([hints[0], hints[1], hints[2], hints[3]]))])
            .collect();
        assert_eq!(decoded, b"hello world");
    }

    /// Downlink bytes written by Xray's server (`newPackedEncoder(...).encode`,
    /// entropy layout) for "RIPDPI sudoku packed downlink!" in two writes,
    /// without and with padding.
    #[test]
    fn decoder_reads_xray_packed_downlink() {
        let vectors = [
            ("244445202105010908070d6529066d4b2d2080", "0807014128664d45290201442b672d4e2b06454e2a620480"),
            (
                "24444516202110050109810807100d651029066d4b862d17208016",
                "820807170113412887664d854529020144812b67172d4e812b0645134e2a62048082",
            ),
        ];
        for (first, second) in vectors {
            let mut decoder = SudokuDecoder::default();
            let mut decoded = decoder.decode_stream_chunk(&hex::decode(first).expect("hex"));
            decoded.extend(decoder.decode_stream_chunk(&hex::decode(second).expect("hex")));
            assert_eq!(decoded, b"RIPDPI sudoku packed downlink!");
        }
    }
}
