use crate::{AutoTtlConfig, ConfigError, NumericRange, OffsetBase, OffsetExpr};

fn parse_range_core(spec: &str, option: &str, minimum: i64) -> Result<NumericRange<i64>, ConfigError> {
    let trimmed = spec.trim();
    if trimmed.is_empty() {
        return Err(ConfigError::invalid(option, Some(spec)));
    }
    let (start_raw, end_raw) = match trimmed.split_once('-') {
        Some((start, end)) if !start.is_empty() && !end.is_empty() => (start, end),
        None => (trimmed, trimmed),
        _ => return Err(ConfigError::invalid(option, Some(spec))),
    };
    let start = start_raw.parse::<i64>().map_err(|_| ConfigError::invalid(option, Some(spec)))?;
    let end = end_raw.parse::<i64>().map_err(|_| ConfigError::invalid(option, Some(spec)))?;
    if start < minimum || end < minimum || start > end {
        return Err(ConfigError::invalid(option, Some(spec)));
    }
    Ok(NumericRange::new(start, end))
}

pub fn parse_round_range_spec(spec: &str) -> Result<NumericRange<i64>, ConfigError> {
    parse_range_core(spec, "--round", 1)
}

pub fn parse_payload_size_range_spec(spec: &str) -> Result<NumericRange<i64>, ConfigError> {
    parse_range_core(spec, "--payload-size-range", 0)
}

pub fn parse_stream_byte_range_spec(spec: &str) -> Result<NumericRange<i64>, ConfigError> {
    parse_range_core(spec, "--stream-byte-range", 0)
}

pub fn parse_auto_ttl_spec(spec: &str) -> Result<AutoTtlConfig, ConfigError> {
    let trimmed = spec.trim();
    let (delta_raw, range_raw) =
        trimmed.split_once(',').ok_or_else(|| ConfigError::invalid("--auto-ttl", Some(spec)))?;
    let delta = delta_raw.trim().parse::<i8>().map_err(|_| ConfigError::invalid("--auto-ttl", Some(spec)))?;
    let (min_raw, max_raw) = range_raw.split_once('-').ok_or_else(|| ConfigError::invalid("--auto-ttl", Some(spec)))?;
    let min_ttl = min_raw.trim().parse::<u16>().map_err(|_| ConfigError::invalid("--auto-ttl", Some(spec)))?;
    let max_ttl = max_raw.trim().parse::<u16>().map_err(|_| ConfigError::invalid("--auto-ttl", Some(spec)))?;
    if min_ttl == 0 || max_ttl == 0 || min_ttl > max_ttl || max_ttl > 255 {
        return Err(ConfigError::invalid("--auto-ttl", Some(spec)));
    }
    Ok(AutoTtlConfig { delta, min_ttl: min_ttl as u8, max_ttl: max_ttl as u8 })
}

fn parse_named_offset_expr(spec: &str) -> Result<Option<OffsetExpr>, ConfigError> {
    let Some(split_at) = spec.char_indices().skip(1).find_map(|(idx, ch)| matches!(ch, '+' | '-').then_some(idx))
    else {
        return Ok(marker_from_name(spec).map(|base| OffsetExpr::marker(base, 0)));
    };
    let name = &spec[..split_at];
    let Some(base) = marker_from_name(name) else {
        return Ok(None);
    };
    let delta = spec[split_at..].parse::<i64>().map_err(|_| ConfigError::invalid("offset", Some(spec)))?;
    Ok(Some(OffsetExpr::marker(base, delta)))
}

fn parse_adaptive_offset_expr(spec: &str) -> Result<Option<OffsetExpr>, ConfigError> {
    let trimmed = spec.trim();
    let Some(inner) = trimmed.strip_prefix("auto(").and_then(|value| value.strip_suffix(')')) else {
        return Ok(None);
    };
    let base = match inner.trim().to_ascii_lowercase().as_str() {
        "balanced" => OffsetBase::AutoBalanced,
        "host" => OffsetBase::AutoHost,
        "midsld" => OffsetBase::AutoMidSld,
        "endhost" => OffsetBase::AutoEndHost,
        "method" => OffsetBase::AutoMethod,
        "sniext" => OffsetBase::AutoSniExt,
        "extlen" => OffsetBase::AutoExtLen,
        _ => return Err(ConfigError::invalid("offset", Some(spec))),
    };
    Ok(Some(OffsetExpr::adaptive(base)))
}

fn marker_from_name(name: &str) -> Option<OffsetBase> {
    match name {
        "abs" => Some(OffsetBase::Abs),
        "host" => Some(OffsetBase::Host),
        "endhost" => Some(OffsetBase::EndHost),
        "sld" => Some(OffsetBase::Sld),
        "midsld" => Some(OffsetBase::MidSld),
        "endsld" => Some(OffsetBase::EndSld),
        "method" => Some(OffsetBase::Method),
        "extlen" => Some(OffsetBase::ExtLen),
        "sniext" => Some(OffsetBase::SniExt),
        _ => None,
    }
}

pub fn parse_offset_expr(spec: &str) -> Result<OffsetExpr, ConfigError> {
    let mut parts = spec.split(':');
    let base = parts.next().ok_or_else(|| ConfigError::invalid("offset", Some(spec)))?;
    let repeats = match parts.next() {
        Some(value) => {
            let parsed = value.parse::<i32>().map_err(|_| ConfigError::invalid("offset", Some(spec)))?;
            if parsed <= 0 {
                return Err(ConfigError::invalid("offset", Some(spec)));
            }
            parsed
        }
        None => 0,
    };
    let skip = match parts.next() {
        Some(value) => value.parse::<i32>().map_err(|_| ConfigError::invalid("offset", Some(spec)))?,
        None => 0,
    };

    let expr = if let Ok(delta) = base.parse::<i64>() {
        OffsetExpr::absolute(delta)
    } else if let Some(expr) = parse_adaptive_offset_expr(base)? {
        expr
    } else if let Some(expr) = parse_named_offset_expr(base)? {
        expr
    } else {
        return Err(ConfigError::invalid("offset", Some(spec)));
    };

    if expr.base.is_adaptive() && (repeats != 0 || skip != 0) {
        return Err(ConfigError::invalid("offset", Some(spec)));
    }

    Ok(expr.with_repeat_skip(repeats, skip))
}
