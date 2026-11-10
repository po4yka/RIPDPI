use crate::wire::DecodeError;

/// Decode the current Flow without applying configuration aliases.
pub(crate) fn decode_flow(mut bytes: &[u8]) -> Result<&str, DecodeError> {
    if bytes.len() > usize::from(u8::MAX) {
        return Err(DecodeError::InvalidAddons);
    }
    let mut flow = "";
    while !bytes.is_empty() {
        let (number, wire_type) = read_tag(&mut bytes)?;
        if number == 1 && wire_type == 2 {
            let length = usize::try_from(read_varint(&mut bytes)?).map_err(|_| DecodeError::InvalidAddons)?;
            let (value, remaining) = bytes.split_at_checked(length).ok_or(DecodeError::InvalidAddons)?;
            flow = std::str::from_utf8(value).map_err(|_| DecodeError::InvalidAddons)?;
            bytes = remaining;
        } else {
            skip_field(&mut bytes, number, wire_type)?;
        }
    }
    Ok(flow)
}

fn read_tag(bytes: &mut &[u8]) -> Result<(u32, u8), DecodeError> {
    let tag = read_varint(bytes)?;
    let number = tag >> 3;
    if number == 0 || number > (1 << 29) - 1 {
        return Err(DecodeError::InvalidAddons);
    }
    Ok((number as u32, (tag & 7) as u8))
}

fn read_varint(bytes: &mut &[u8]) -> Result<u64, DecodeError> {
    let mut value = 0;
    for shift in (0..=63).step_by(7) {
        let byte = *bytes.first().ok_or(DecodeError::InvalidAddons)?;
        *bytes = &bytes[1..];
        if shift == 63 && byte > 1 {
            return Err(DecodeError::InvalidAddons);
        }
        value |= u64::from(byte & 0x7f) << shift;
        if byte & 0x80 == 0 {
            return Ok(value);
        }
    }
    Err(DecodeError::InvalidAddons)
}

fn skip_field(bytes: &mut &[u8], number: u32, wire_type: u8) -> Result<(), DecodeError> {
    let length = match wire_type {
        0 => {
            read_varint(bytes)?;
            return Ok(());
        }
        1 => 8,
        2 => usize::try_from(read_varint(bytes)?).map_err(|_| DecodeError::InvalidAddons)?,
        // At most 127 groups can fit in the bounded 255-byte block.
        3 => loop {
            let (nested_number, nested_type) = read_tag(bytes)?;
            if nested_type == 4 {
                return if nested_number == number { Ok(()) } else { Err(DecodeError::InvalidAddons) };
            }
            skip_field(bytes, nested_number, nested_type)?;
        },
        5 => 4,
        _ => return Err(DecodeError::InvalidAddons),
    };
    *bytes = bytes.get(length..).ok_or(DecodeError::InvalidAddons)?;
    Ok(())
}
