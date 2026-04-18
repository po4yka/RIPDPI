use std::io;
use std::sync::{Arc, OnceLock};

use hex::FromHex;
use rand::RngExt;
use sha2::{Digest, Sha256};
use tokio::io::{AsyncRead, AsyncReadExt, AsyncWrite, AsyncWriteExt};

use crate::AsyncIo;
use crate::FinalmaskConfig;

const TCP_BRIDGE_BUFFER_SIZE: usize = 64 * 1024;
const TCP_COPY_BUFFER_SIZE: usize = 16 * 1024;

type BoxedIo = Box<dyn AsyncIo>;

#[derive(Clone)]
enum FinalmaskSpec {
    HeaderCustom { header: Vec<u8>, trailer: Vec<u8>, rand_range: Option<(usize, usize)> },
    Noise { rand_range: (usize, usize) },
    Fragment { packets: usize, min_bytes: usize, max_bytes: usize },
    Sudoku { table: Arc<SudokuTable> },
}

impl FinalmaskSpec {
    fn from_config(config: &FinalmaskConfig) -> io::Result<Option<Self>> {
        let kind = config.r#type.trim();
        if kind.is_empty() || kind == "off" {
            return Ok(None);
        }
        let rand_range = parse_rand_range(&config.rand_range)?;
        let spec = match kind {
            "header_custom" => Self::HeaderCustom {
                header: decode_hex("headerHex", &config.header_hex)?,
                trailer: decode_hex("trailerHex", &config.trailer_hex)?,
                rand_range,
            },
            "noise" => Self::Noise {
                rand_range: rand_range.ok_or_else(|| {
                    io::Error::new(
                        io::ErrorKind::InvalidInput,
                        "finalmask noise requires randRange in min-max format",
                    )
                })?,
            },
            "fragment" => Self::Fragment {
                packets: usize::try_from(config.fragment_packets).unwrap_or_default(),
                min_bytes: usize::try_from(config.fragment_min_bytes).unwrap_or_default(),
                max_bytes: usize::try_from(config.fragment_max_bytes).unwrap_or_default(),
            },
            "sudoku" => Self::Sudoku { table: Arc::new(SudokuTable::new(config.sudoku_seed.trim())?) },
            other => {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidInput,
                    format!("unsupported xHTTP finalmask type {other}"),
                ));
            }
        };
        Ok(Some(spec))
    }
}

pub fn wrap_tcp_stream<S>(stream: S, config: &FinalmaskConfig) -> io::Result<BoxedIo>
where
    S: AsyncRead + AsyncWrite + Unpin + Send + 'static,
{
    let Some(spec) = FinalmaskSpec::from_config(config)? else {
        return Ok(Box::new(stream));
    };

    let (app_side, bridge_side) = tokio::io::duplex(TCP_BRIDGE_BUFFER_SIZE);
    let (mut bridge_read, mut bridge_write) = tokio::io::split(bridge_side);
    let (mut upstream_read, mut upstream_write) = tokio::io::split(stream);
    let mut outbound = TcpOutboundMask::new(spec.clone());
    let mut inbound = TcpInboundMask::new(spec);

    tokio::spawn(async move {
        let result = async {
            let mut buffer = vec![0u8; TCP_COPY_BUFFER_SIZE];
            loop {
                let read = bridge_read.read(&mut buffer).await?;
                if read == 0 {
                    upstream_write.shutdown().await?;
                    return Ok::<(), io::Error>(());
                }
                for frame in outbound.encode(&buffer[..read])? {
                    upstream_write.write_all(&frame).await?;
                }
            }
        }
        .await;
        if let Err(error) = result {
            tracing::debug!(error = %error, "xHTTP finalmask uplink bridge stopped");
        }
    });

    tokio::spawn(async move {
        let result = async {
            let mut buffer = vec![0u8; TCP_COPY_BUFFER_SIZE];
            loop {
                let read = upstream_read.read(&mut buffer).await?;
                if read == 0 {
                    bridge_write.shutdown().await?;
                    return Ok::<(), io::Error>(());
                }
                let decoded = inbound.decode(&buffer[..read])?;
                if !decoded.is_empty() {
                    bridge_write.write_all(&decoded).await?;
                }
            }
        }
        .await;
        if let Err(error) = result {
            tracing::debug!(error = %error, "xHTTP finalmask downlink bridge stopped");
        }
    });

    Ok(Box::new(app_side))
}

struct TcpOutboundMask {
    spec: FinalmaskSpec,
    prelude_sent: bool,
    sudoku_encoder: Option<SudokuEncoder>,
}

impl TcpOutboundMask {
    fn new(spec: FinalmaskSpec) -> Self {
        let sudoku_encoder = match &spec {
            FinalmaskSpec::Sudoku { table } => Some(SudokuEncoder::new(Arc::clone(table))),
            _ => None,
        };
        Self { spec, prelude_sent: false, sudoku_encoder }
    }

    fn encode(&mut self, payload: &[u8]) -> io::Result<Vec<Vec<u8>>> {
        match &self.spec {
            FinalmaskSpec::HeaderCustom { header, trailer, rand_range } => {
                let mut frames = Vec::with_capacity(2);
                if !self.prelude_sent {
                    let mut prelude = Vec::with_capacity(header.len() + trailer.len() + 64);
                    prelude.extend_from_slice(header);
                    if let Some((min, max)) = rand_range {
                        prelude.extend_from_slice(&random_bytes(*min, *max));
                    }
                    prelude.extend_from_slice(trailer);
                    if !prelude.is_empty() {
                        frames.push(prelude);
                    }
                    self.prelude_sent = true;
                }
                if !payload.is_empty() {
                    frames.push(payload.to_vec());
                }
                Ok(frames)
            }
            FinalmaskSpec::Noise { rand_range } => {
                let mut frames = Vec::with_capacity(2);
                if !self.prelude_sent {
                    let prelude = random_bytes(rand_range.0, rand_range.1);
                    if !prelude.is_empty() {
                        frames.push(prelude);
                    }
                    self.prelude_sent = true;
                }
                if !payload.is_empty() {
                    frames.push(payload.to_vec());
                }
                Ok(frames)
            }
            FinalmaskSpec::Fragment { packets, min_bytes, max_bytes } => {
                Ok(fragment_bytes(payload, *packets, *min_bytes, *max_bytes))
            }
            FinalmaskSpec::Sudoku { .. } => Ok(vec![self
                .sudoku_encoder
                .as_mut()
                .ok_or_else(|| io::Error::other("missing sudoku encoder"))?
                .encode_chunk(payload)?]),
        }
    }
}

struct TcpInboundMask {
    sudoku_decoder: Option<SudokuDecoder>,
}

impl TcpInboundMask {
    fn new(spec: FinalmaskSpec) -> Self {
        let sudoku_decoder = match &spec {
            FinalmaskSpec::Sudoku { table } => Some(SudokuDecoder::new(Arc::clone(table))),
            _ => None,
        };
        Self { sudoku_decoder }
    }

    fn decode(&mut self, payload: &[u8]) -> io::Result<Vec<u8>> {
        match &mut self.sudoku_decoder {
            Some(decoder) => decoder.decode_stream_chunk(payload),
            None => Ok(payload.to_vec()),
        }
    }
}

fn decode_hex(label: &str, value: &str) -> io::Result<Vec<u8>> {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return Ok(Vec::new());
    }
    Vec::from_hex(trimmed)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid finalmask {label}: {error}")))
}

fn parse_rand_range(value: &str) -> io::Result<Option<(usize, usize)>> {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return Ok(None);
    }
    let (min_raw, max_raw) = trimmed
        .split_once('-')
        .ok_or_else(|| io::Error::new(io::ErrorKind::InvalidInput, "finalmask randRange must use min-max format"))?;
    let min = min_raw.trim().parse::<usize>().map_err(|error| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("invalid finalmask randRange minimum: {error}"))
    })?;
    let max = max_raw.trim().parse::<usize>().map_err(|error| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("invalid finalmask randRange maximum: {error}"))
    })?;
    if min > max {
        return Err(io::Error::new(io::ErrorKind::InvalidInput, "finalmask randRange minimum must not exceed maximum"));
    }
    Ok(Some((min, max)))
}

fn random_bytes(min: usize, max: usize) -> Vec<u8> {
    if max == 0 {
        return Vec::new();
    }
    let mut rng = rand::rng();
    let len = if min >= max { min } else { rng.random_range(min..=max) };
    (0..len).map(|_| rng.random::<u8>()).collect()
}

fn fragment_bytes(payload: &[u8], packets: usize, min_bytes: usize, max_bytes: usize) -> Vec<Vec<u8>> {
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

#[derive(Clone)]
struct SudokuTable {
    encode: Vec<Vec<[u8; 4]>>,
    decode: Arc<Vec<(u32, u8)>>,
}

impl SudokuTable {
    fn new(password: &str) -> io::Result<Self> {
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

struct SudokuEncoder {
    table: Arc<SudokuTable>,
}

impl SudokuEncoder {
    fn new(table: Arc<SudokuTable>) -> Self {
        Self { table }
    }

    fn encode_chunk(&mut self, payload: &[u8]) -> io::Result<Vec<u8>> {
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

struct SudokuDecoder {
    table: Arc<SudokuTable>,
    hints: Vec<u8>,
}

impl SudokuDecoder {
    fn new(table: Arc<SudokuTable>) -> Self {
        Self { table, hints: Vec::with_capacity(4) }
    }

    fn decode_stream_chunk(&mut self, payload: &[u8]) -> io::Result<Vec<u8>> {
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

#[cfg(test)]
mod tests {
    use super::*;

    fn header_config() -> FinalmaskConfig {
        FinalmaskConfig {
            r#type: "header_custom".to_string(),
            header_hex: "abcd".to_string(),
            trailer_hex: "ef01".to_string(),
            rand_range: "2-2".to_string(),
            ..FinalmaskConfig::default()
        }
    }

    #[test]
    fn header_custom_tcp_prelude_is_emitted_once() {
        let mut mask =
            TcpOutboundMask::new(FinalmaskSpec::from_config(&header_config()).expect("config").expect("mask"));

        let first = mask.encode(b"hello").expect("first");
        let second = mask.encode(b"world").expect("second");

        assert_eq!(2, first.len());
        assert_eq!(b"hello", first[1].as_slice());
        assert_eq!(1, second.len());
        assert_eq!(b"world", second[0].as_slice());
    }

    #[test]
    fn fragment_mode_splits_payload_into_requested_frames() {
        let config = FinalmaskConfig {
            r#type: "fragment".to_string(),
            fragment_packets: 3,
            fragment_min_bytes: 2,
            fragment_max_bytes: 4,
            ..FinalmaskConfig::default()
        };
        let mut mask = TcpOutboundMask::new(FinalmaskSpec::from_config(&config).expect("config").expect("mask"));

        let frames = mask.encode(b"abcdefgh").expect("frames");

        assert_eq!(3, frames.len());
        assert_eq!(8, frames.iter().map(Vec::len).sum::<usize>());
    }

    #[test]
    fn noise_mode_emits_random_prelude_once_then_passes_payload() {
        let config = FinalmaskConfig {
            r#type: "noise".to_string(),
            rand_range: "4-4".to_string(),
            ..FinalmaskConfig::default()
        };
        let mut mask = TcpOutboundMask::new(FinalmaskSpec::from_config(&config).expect("config").expect("mask"));

        let first = mask.encode(b"hello").expect("first");
        let second = mask.encode(b"world").expect("second");

        assert_eq!(2, first.len());
        assert_eq!(4, first[0].len());
        assert_eq!(b"hello", first[1].as_slice());
        assert_eq!(1, second.len());
        assert_eq!(b"world", second[0].as_slice());
    }

    #[test]
    fn sudoku_round_trips_stream_payload() {
        let config = FinalmaskConfig {
            r#type: "sudoku".to_string(),
            sudoku_seed: "fixture-seed".to_string(),
            ..FinalmaskConfig::default()
        };
        let spec = FinalmaskSpec::from_config(&config).expect("config").expect("mask");
        let FinalmaskSpec::Sudoku { table } = spec else {
            unreachable!();
        };
        let mut encoder = SudokuEncoder::new(Arc::clone(&table));
        let encoded = encoder.encode_chunk(b"hello world").expect("encode");
        let mut decoder = SudokuDecoder::new(table);
        let decoded = decoder.decode_stream_chunk(&encoded).expect("decode");

        assert_eq!(b"hello world", decoded.as_slice());
    }

    #[tokio::test]
    async fn tcp_wrapper_preserves_plain_streams_when_off() {
        let (mut left, right) = tokio::io::duplex(1024);
        let wrapped = wrap_tcp_stream(right, &FinalmaskConfig::default()).expect("wrapped");

        tokio::spawn(async move {
            left.write_all(b"hello").await.expect("write");
        });

        let mut stream = wrapped;
        let mut buffer = [0u8; 5];
        stream.read_exact(&mut buffer).await.expect("read");
        assert_eq!(b"hello", &buffer);
    }
}
