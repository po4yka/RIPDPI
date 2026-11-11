use std::io;
use std::sync::Arc;

use hex::FromHex;

use crate::config::{FinalmaskConfig, parse_finalmask_rand_range};

use super::sudoku::SudokuTable;

#[derive(Clone)]
pub(crate) enum FinalmaskSpec {
    HeaderCustom { header: Vec<u8>, trailer: Vec<u8>, rand_range: Option<(usize, usize)> },
    Noise { rand_range: (usize, usize) },
    Fragment { packets: usize, min_bytes: usize, max_bytes: usize },
    Sudoku { table: Arc<SudokuTable> },
}

impl FinalmaskSpec {
    pub(crate) fn from_config(config: &FinalmaskConfig) -> io::Result<Option<Self>> {
        config.validate()?;
        let kind = config.r#type.trim();
        if kind.is_empty() || kind == "off" {
            return Ok(None);
        }
        let spec = match kind {
            "header_custom" => Self::HeaderCustom {
                header: decode_hex("headerHex", &config.header_hex)?,
                trailer: decode_hex("trailerHex", &config.trailer_hex)?,
                rand_range: parse_finalmask_rand_range(&config.rand_range)?,
            },
            "noise" => Self::Noise {
                rand_range: parse_finalmask_rand_range(&config.rand_range)?.ok_or_else(|| {
                    io::Error::new(io::ErrorKind::InvalidInput, "finalmask noise requires randRange in min-max format")
                })?,
            },
            "fragment" => Self::Fragment {
                packets: parse_non_negative("fragmentPackets", config.fragment_packets)?,
                min_bytes: parse_non_negative("fragmentMinBytes", config.fragment_min_bytes)?,
                max_bytes: parse_non_negative("fragmentMaxBytes", config.fragment_max_bytes)?,
            },
            "sudoku" => Self::Sudoku { table: Arc::new(SudokuTable::new(&config.sudoku_seed)?) },
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

fn decode_hex(label: &str, value: &str) -> io::Result<Vec<u8>> {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return Ok(Vec::new());
    }
    Vec::from_hex(trimmed)
        .map_err(|error| io::Error::new(io::ErrorKind::InvalidInput, format!("invalid finalmask {label}: {error}")))
}

fn parse_non_negative(label: &str, value: i32) -> io::Result<usize> {
    usize::try_from(value).map_err(|_| {
        io::Error::new(io::ErrorKind::InvalidInput, format!("finalmask {label} must be non-negative, got {value}"))
    })
}
