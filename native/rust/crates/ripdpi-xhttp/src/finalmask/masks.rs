use std::io;
use std::sync::Arc;

use rand::RngExt;

use super::fragmentation::fragment_bytes;
use super::spec::FinalmaskSpec;
use super::sudoku::{SudokuDecoder, SudokuEncoder};

pub(crate) struct TcpOutboundMask {
    spec: FinalmaskSpec,
    prelude_sent: bool,
    sudoku_encoder: Option<SudokuEncoder>,
}

impl TcpOutboundMask {
    pub(crate) fn new(spec: FinalmaskSpec) -> Self {
        let sudoku_encoder = match &spec {
            FinalmaskSpec::Sudoku { table } => Some(SudokuEncoder::new(Arc::clone(table))),
            _ => None,
        };
        Self { spec, prelude_sent: false, sudoku_encoder }
    }

    pub(crate) fn encode(&mut self, payload: &[u8]) -> io::Result<Vec<Vec<u8>>> {
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
            FinalmaskSpec::Sudoku { .. } => Ok(vec![
                self.sudoku_encoder
                    .as_mut()
                    .ok_or_else(|| io::Error::other("missing sudoku encoder"))?
                    .encode_chunk(payload)?,
            ]),
        }
    }
}

pub(crate) struct TcpInboundMask {
    sudoku_decoder: Option<SudokuDecoder>,
}

impl TcpInboundMask {
    pub(crate) fn new(spec: FinalmaskSpec) -> Self {
        let sudoku_decoder = match &spec {
            FinalmaskSpec::Sudoku { table } => Some(SudokuDecoder::new(Arc::clone(table))),
            _ => None,
        };
        Self { sudoku_decoder }
    }

    pub(crate) fn decode(&mut self, payload: &[u8]) -> io::Result<Vec<u8>> {
        match &mut self.sudoku_decoder {
            Some(decoder) => decoder.decode_stream_chunk(payload),
            None => Ok(payload.to_vec()),
        }
    }
}

fn random_bytes(min: usize, max: usize) -> Vec<u8> {
    if max == 0 {
        return Vec::new();
    }
    let mut rng = rand::rng();
    let len = if min >= max { min } else { rng.random_range(min..=max) };
    (0..len).map(|_| rng.random::<u8>()).collect()
}
