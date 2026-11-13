use super::{Value, collect_field_values, field_u64, is_tcp_outbound, split_oracle_payload};

pub(super) fn assert_tcp_disoob(packets: &[Value], port: u16, original: &[u8], boundary: usize) -> Result<(), String> {
    if boundary >= original.len() {
        return Err("Disoob fixture must have a prefix and tail".to_string());
    }
    // The scenario fixes the restored TTL at 64 and inserts X at its marker.
    let mut expected = original.to_vec();
    expected.insert(boundary, b'X');
    let urgent_end = boundary + 1;
    let mut stream = None;
    let mut segments = Vec::new();
    for packet in packets.iter().filter(|packet| is_tcp_outbound(packet, port)) {
        let length = field_u64(packet, "tcp.len").ok_or("missing TCP payload length")?;
        if length == 0 {
            if collect_field_values(packet, "tcp.payload").iter().any(|value| !value.is_empty()) {
                return Err("empty TCP segment contains captured payload".to_string());
            }
            continue;
        }
        let current_stream = field_u64(packet, "tcp.stream").ok_or("missing TCP stream identity")?;
        if stream.replace(current_stream).is_some_and(|previous| previous != current_stream) {
            return Err("Disoob request spans multiple TCP streams".to_string());
        }
        let sequence = field_u64(packet, "tcp.seq_raw")
            .and_then(|value| u32::try_from(value).ok())
            .ok_or("missing or invalid raw TCP sequence")?;
        let payloads = collect_field_values(packet, "tcp.payload");
        let [payload] = payloads.as_slice() else {
            return Err("expected one TCP payload field".to_string());
        };
        let payload = split_oracle_payload(payload, expected.len()).ok_or("invalid TCP payload hex")?;
        if payload.len() as u64 != length {
            return Err("TCP payload length disagrees with captured bytes".to_string());
        }
        let ttl = field_u64(packet, "ip.ttl").or_else(|| field_u64(packet, "ipv6.hlim")).ok_or("missing TTL")?;
        let flags = collect_field_values(packet, "tcp.flags.urg");
        let urgent = match flags.as_slice() {
            [value] if matches!(value.as_str(), "1" | "True" | "true") => true,
            [value] if matches!(value.as_str(), "0" | "False" | "false") => false,
            _ => return Err("missing or invalid TCP URG flag".to_string()),
        };
        let urgent_pointer = if urgent {
            Some(
                field_u64(packet, "tcp.urgent_pointer")
                    .and_then(|value| u16::try_from(value).ok())
                    .filter(|value| *value != 0)
                    .ok_or("missing or invalid urgent pointer")?,
            )
        } else {
            None
        };
        segments.push((sequence, payload, ttl, urgent_pointer));
    }
    let Some((anchor, ..)) = segments.first() else {
        return Err("no Disoob request payload captured".to_string());
    };
    // The small fixture can cross the u32 sequence wrap or arrive out of order.
    let base = segments
        .iter()
        .map(|(sequence, ..)| *sequence)
        .min_by_key(|sequence| sequence.wrapping_sub(*anchor) as i32)
        .ok_or("missing Disoob sequence origin")?;
    let urgent_sequence = base.wrapping_add(u32::try_from(urgent_end).map_err(|_| "fixture is too large")?);
    let mut covered = vec![false; expected.len()];
    let mut low_ttl_prefix = vec![false; urgent_end];
    let mut urgent_seen = false;
    for (sequence, payload, ttl, pointer) in segments {
        let offset = sequence.wrapping_sub(base) as usize;
        let end = offset.checked_add(payload.len()).ok_or("TCP sequence range overflow")?;
        if expected.get(offset..end) != Some(payload.as_slice()) {
            return Err("Disoob bytes differ from the original request plus one urgent byte".to_string());
        }
        if !matches!(ttl, 1 | 64) || (end > urgent_end && ttl != 64) {
            return Err("Disoob TTL must be 1 before the tail and restored to 64 for the tail".to_string());
        }
        if let Some(pointer) = pointer {
            if sequence.wrapping_add(u32::from(pointer)) != urgent_sequence {
                return Err("urgent pointer does not end after the inserted byte".to_string());
            }
            urgent_seen |= ttl == 1;
        }
        covered[offset..end].fill(true);
        if ttl == 1 && offset < urgent_end {
            low_ttl_prefix[offset..end.min(urgent_end)].fill(true);
        }
    }
    if !urgent_seen || !covered.into_iter().all(|byte| byte) || !low_ttl_prefix.into_iter().all(|byte| byte) {
        return Err("incomplete Disoob bytes, low-TTL prefix, or urgent indication".to_string());
    }
    Ok(())
}

#[cfg(test)]
#[path = "disoob/tests.rs"]
mod tests;
