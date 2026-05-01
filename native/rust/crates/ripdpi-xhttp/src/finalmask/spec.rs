use std::io;
use std::sync::Arc;

use hex::FromHex;

use crate::config::FinalmaskConfig;

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
                    io::Error::new(io::ErrorKind::InvalidInput, "finalmask noise requires randRange in min-max format")
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
