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
    let (delta_raw, range_raw) =
        spec.trim().split_once(',').ok_or_else(|| ConfigError::invalid("--auto-ttl", Some(spec)))?;
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
        "echext" => Some(OffsetBase::EchExt),
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

#[cfg(test)]
mod tests {
    use super::*;
    use crate::{NumericRange, OffsetBase, OffsetExpr};

    #[test]
    fn parse_offset_expr_repeat_and_skip_values() {
        let repeated = parse_offset_expr("host+2:3").unwrap();
        assert_eq!(repeated, OffsetExpr::marker(OffsetBase::Host, 2).with_repeat_skip(3, 0));

        let repeated_with_skip = parse_offset_expr("endhost-1:2:1").unwrap();
        assert_eq!(repeated_with_skip, OffsetExpr::marker(OffsetBase::EndHost, -1).with_repeat_skip(2, 1));
    }

    #[test]
    fn parse_offset_expr_named_markers() {
        assert_eq!(parse_offset_expr("method+2").unwrap(), OffsetExpr::marker(OffsetBase::Method, 2));
        assert_eq!(parse_offset_expr("midsld").unwrap(), OffsetExpr::marker(OffsetBase::MidSld, 0));
        assert_eq!(parse_offset_expr("midsld-1").unwrap(), OffsetExpr::marker(OffsetBase::MidSld, -1));
        assert_eq!(parse_offset_expr("echext").unwrap(), OffsetExpr::marker(OffsetBase::EchExt, 0));
        assert_eq!(parse_offset_expr("echext+4").unwrap(), OffsetExpr::marker(OffsetBase::EchExt, 4));
        assert_eq!(parse_offset_expr("sniext+4").unwrap(), OffsetExpr::marker(OffsetBase::SniExt, 4));
        assert_eq!(parse_offset_expr("extlen").unwrap(), OffsetExpr::marker(OffsetBase::ExtLen, 0));
        assert_eq!(parse_offset_expr("abs-5").unwrap(), OffsetExpr::absolute(-5));
        assert_eq!(parse_offset_expr("-5").unwrap(), OffsetExpr::absolute(-5));
    }

    #[test]
    fn parse_offset_expr_adaptive_markers() {
        assert_eq!(parse_offset_expr("auto(balanced)").unwrap(), OffsetExpr::adaptive(OffsetBase::AutoBalanced));
        assert_eq!(parse_offset_expr("auto(host)").unwrap(), OffsetExpr::adaptive(OffsetBase::AutoHost));
        assert_eq!(parse_offset_expr("auto(midsld)").unwrap(), OffsetExpr::adaptive(OffsetBase::AutoMidSld));
        assert_eq!(parse_offset_expr("auto(endhost)").unwrap(), OffsetExpr::adaptive(OffsetBase::AutoEndHost));
        assert_eq!(parse_offset_expr("auto(method)").unwrap(), OffsetExpr::adaptive(OffsetBase::AutoMethod));
        assert_eq!(parse_offset_expr("auto(sniext)").unwrap(), OffsetExpr::adaptive(OffsetBase::AutoSniExt));
        assert_eq!(parse_offset_expr("auto(extlen)").unwrap(), OffsetExpr::adaptive(OffsetBase::AutoExtLen));
    }

    #[test]
    fn parse_offset_expr_rejects_invalid_marker_syntax() {
        for spec in [
            "host+",
            "midsld-",
            "unknown",
            "host+nope",
            "5+zz",
            "1+ss",
            "5+se",
            "3+hm",
            "0+nr",
            "method++1",
            "auto()",
            "auto(foo)",
            "auto(echext)",
        ] {
            assert!(parse_offset_expr(spec).is_err(), "{spec} should be rejected");
        }
    }

    #[test]
    fn parse_activation_range_specs_validate_bounds() {
        assert_eq!(parse_round_range_spec("1-3").unwrap(), NumericRange::new(1, 3));
        assert_eq!(parse_payload_size_range_spec("64-512").unwrap(), NumericRange::new(64, 512));
        assert_eq!(parse_stream_byte_range_spec("0-1199").unwrap(), NumericRange::new(0, 1199));

        assert!(parse_round_range_spec("0-2").is_err());
        assert!(parse_payload_size_range_spec("-1-5").is_err());
        assert!(parse_stream_byte_range_spec("10-2").is_err());
    }

    #[test]
    fn parse_auto_ttl_spec_validates_bounds() {
        assert_eq!(parse_auto_ttl_spec("-1,3-12").unwrap(), AutoTtlConfig { delta: -1, min_ttl: 3, max_ttl: 12 });
        assert_eq!(parse_auto_ttl_spec("0,8-8").unwrap(), AutoTtlConfig { delta: 0, min_ttl: 8, max_ttl: 8 });

        for value in ["", "-1", "-1,3", "-1,0-12", "-1,12-3", "-1,3-256", "x,3-12"] {
            assert!(parse_auto_ttl_spec(value).is_err(), "{value} should fail");
        }
    }

    #[test]
    fn parse_offset_expr_adaptive_rejects_repeat_skip() {
        assert!(parse_offset_expr("auto(balanced):2").is_err());
        assert!(parse_offset_expr("auto(host):1:1").is_err());
    }

    #[test]
    fn parse_round_range_spec_single_value_is_point_range() {
        let r = parse_round_range_spec("5").unwrap();
        assert_eq!(r, NumericRange::new(5, 5));
    }

    #[test]
    fn parse_round_range_spec_empty_rejected() {
        assert!(parse_round_range_spec("").is_err());
        assert!(parse_round_range_spec("  ").is_err());
    }

    #[test]
    fn parse_round_range_spec_inverted_range_rejected() {
        assert!(parse_round_range_spec("5-2").is_err());
    }

    // --- New coverage gap tests ---

    #[test]
    fn parse_offset_expr_absolute_zero() {
        assert_eq!(parse_offset_expr("0").unwrap(), OffsetExpr::absolute(0));
        assert_eq!(parse_offset_expr("abs+0").unwrap(), OffsetExpr::marker(OffsetBase::Abs, 0));
    }

    #[test]
    fn parse_offset_expr_large_delta() {
        let expr = parse_offset_expr("host+9999").unwrap();
        assert_eq!(expr, OffsetExpr::marker(OffsetBase::Host, 9999));

        let expr = parse_offset_expr("-32768").unwrap();
        assert_eq!(expr, OffsetExpr::absolute(-32768));
    }

    #[test]
    fn parse_stream_byte_range_spec_zero_zero() {
        let r = parse_stream_byte_range_spec("0-0").unwrap();
        assert_eq!(r, NumericRange::new(0, 0));
    }
}
