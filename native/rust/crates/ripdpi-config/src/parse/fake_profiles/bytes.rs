use std::fs;

use crate::ConfigError;

fn cform_byte(ch: char) -> Option<u8> {
    Some(match ch {
        'r' => b'\r',
        'n' => b'\n',
        't' => b'\t',
        '\\' => b'\\',
        'f' => 0x0c,
        'b' => 0x08,
        'v' => 0x0b,
        'a' => 0x07,
        _ => return None,
    })
}

pub fn data_from_str(spec: &str) -> Result<Vec<u8>, ConfigError> {
    if spec.is_empty() {
        return Err(ConfigError::invalid("inline-data", Some(spec)));
    }
    let bytes = spec.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut idx = 0;
    while idx < bytes.len() {
        if bytes[idx] != b'\\' {
            out.push(bytes[idx]);
            idx += 1;
            continue;
        }
        idx += 1;
        if idx >= bytes.len() {
            out.push(b'\\');
            break;
        }
        let ch = bytes[idx] as char;
        if let Some(mapped) = cform_byte(ch) {
            out.push(mapped);
            idx += 1;
            continue;
        }
        if ch == 'x' && idx + 2 < bytes.len() {
            let hex = &spec[idx + 1..idx + 3];
            if let Ok(value) = u8::from_str_radix(hex, 16) {
                out.push(value);
                idx += 3;
                continue;
            }
        }
        let mut oct_end = idx;
        while oct_end < bytes.len() && oct_end < idx + 3 && (b'0'..=b'7').contains(&bytes[oct_end]) {
            oct_end += 1;
        }
        if oct_end > idx
            && let Ok(value) = u8::from_str_radix(&spec[idx..oct_end], 8)
        {
            out.push(value);
            idx = oct_end;
            continue;
        }
        out.push(ch as u8);
        idx += 1;
    }
    if out.is_empty() {
        return Err(ConfigError::invalid("inline-data", Some(spec)));
    }
    Ok(out)
}

pub fn file_or_inline_bytes(spec: &str) -> Result<Vec<u8>, ConfigError> {
    if let Some(inline) = spec.strip_prefix(':') {
        return data_from_str(inline);
    }
    let data = fs::read(spec).map_err(|_| ConfigError::invalid("file", Some(spec)))?;
    if data.is_empty() {
        return Err(ConfigError::invalid("file", Some(spec)));
    }
    Ok(data)
}
