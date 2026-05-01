use std::io;
use std::sync::{Arc, OnceLock};

use rand::RngExt;
use sha2::{Digest, Sha256};

#[derive(Clone)]
pub(crate) struct SudokuTable {
    encode: Vec<Vec<[u8; 4]>>,
    decode: Arc<Vec<(u32, u8)>>,
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
        shuffle_with_seed(&mut order, u64::from_be_bytes(seed_bytes));

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
                if let Some(existing) = decode_map.insert(key, value as u8) {
                    if existing != value as u8 {
                        return Err(io::Error::other("sudoku decode collision"));
                    }
                }
                encodings.push(hints);
            }
            encode[usize::from(value)] = encodings;
        }

        Ok(Self { encode, decode: Arc::new(decode_map.into_iter().collect()) })
    }

    fn decode_key(&self, key: u32) -> Option<u8> {
        self.decode.binary_search_by_key(&key, |entry| entry.0).ok().map(|index| self.decode[index].1)
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

pub(crate) struct SudokuDecoder {
    table: Arc<SudokuTable>,
    hints: Vec<u8>,
}

impl SudokuDecoder {
    pub(crate) fn new(table: Arc<SudokuTable>) -> Self {
        Self { table, hints: Vec::with_capacity(4) }
    }

    pub(crate) fn decode_stream_chunk(&mut self, payload: &[u8]) -> io::Result<Vec<u8>> {
        let mut out = Vec::with_capacity(payload.len() / 4 + 1);
        for &byte in payload {
            if !is_entropy_hint(byte) {
                continue;
            }
            self.hints.push(byte);
            if self.hints.len() < 4 {
                continue;
            }
            let hints = [self.hints[0], self.hints[1], self.hints[2], self.hints[3]];
            let key = pack_key(sort4(hints));
            let decoded = self
                .table
                .decode_key(key)
                .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidData, "invalid sudoku hint tuple"))?;
            out.push(decoded);
            self.hints.clear();
        }
        Ok(out)
    }
}

fn encode_group(group: u8) -> u8 {
    let value = group & 0x3f;
    ((value & 0x30) << 1) | (value & 0x0f)
}

fn is_entropy_hint(byte: u8) -> bool {
    (byte & 0x90) == 0
}

fn shuffle_with_seed(values: &mut [usize], seed: u64) {
    let mut state = seed.wrapping_add(0x9e3779b97f4a7c15);
    for index in (1..values.len()).rev() {
        state ^= state >> 12;
        state ^= state << 25;
        state ^= state >> 27;
        let next = state.wrapping_mul(0x2545f4914f6cdd1d);
        let other = (next as usize) % (index + 1);
        values.swap(index, other);
    }
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
