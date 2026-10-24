//! URI authority parsing independent of URL libraries' scalar-port restriction.

use std::collections::HashMap;

use crate::Config;
use crate::error::Result;
use crate::hopping_policy::{MAX_HOP_INTERVAL_MILLIS, PortHoppingConfig, invalid_policy};
use crate::quic_transport::parse_certificate_pin_sha256;

pub(crate) fn parse(input: &str) -> Result<Config> {
    let rest = input
        .strip_prefix("hysteria2://")
        .or_else(|| input.strip_prefix("hy2://"))
        .ok_or_else(|| invalid_policy("unsupported URI scheme"))?;
    let end = rest.find(['/', '?', '#']).unwrap_or(rest.len());
    let authority = &rest[..end];
    let (userinfo, address) = authority.rsplit_once('@').map_or(("", authority), |(user, host)| (user, host));
    let (host, ports) = split_address(address)?;
    let ports = ports.unwrap_or("443");
    let validated =
        PortHoppingConfig::from_wire(Some(ports), None, None)?.ok_or_else(|| invalid_policy("missing ports"))?;
    let scalar = validated.ports()[0];
    let auth_prefix = if userinfo.is_empty() { String::new() } else { format!("{userinfo}@") };
    let parsed = url::Url::parse(&format!("http://{auth_prefix}{host}:{scalar}{}", &rest[end..]))?;
    let mut query = HashMap::new();
    for (key, value) in parsed.query_pairs() {
        if key.eq_ignore_ascii_case("mport") {
            return Err(invalid_policy("unsupported mport parameter"));
        }
        if matches!(key.as_ref(), "hopInterval" | "minHopInterval" | "maxHopInterval")
            && query.contains_key(key.as_ref())
        {
            return Err(invalid_policy("duplicate interval parameter"));
        }
        query.insert(key.into_owned(), value.into_owned());
    }
    let fixed = interval(&query, "hopInterval")?;
    let min = interval(&query, "minHopInterval")?;
    let max = interval(&query, "maxHopInterval")?;
    if fixed.is_some() && (min.is_some() || max.is_some()) || min.is_some() != max.is_some() {
        return Err(invalid_policy("conflicting or incomplete interval window"));
    }
    let hostname = parsed.host_str().ok_or_else(|| invalid_policy("missing host"))?;
    let mut config = Config::from_parts(
        decode_userinfo(userinfo)?,
        hostname,
        i32::from(scalar),
        query.get("sni").cloned().unwrap_or_else(|| hostname.trim_matches(['[', ']']).to_string()),
    )?;
    config.insecure = query.get("insecure").is_some_and(|value| value == "1");
    config.pin_sha256 = query
        .get("pinSHA256")
        .filter(|value| !value.trim().is_empty())
        .map(|value| parse_certificate_pin_sha256(value))
        .transpose()?;
    config.salamander_key = query.get("obfs-password").cloned();
    if ports.contains([',', '-']) || fixed.is_some() || min.is_some() {
        config.port_hopping = PortHoppingConfig::from_wire(Some(ports), fixed.or(min), max)?;
    }
    Ok(config)
}

fn decode_userinfo(raw: &str) -> Result<String> {
    let mut decoded = Vec::with_capacity(raw.len());
    let mut bytes = raw.bytes();
    while let Some(byte) = bytes.next() {
        if byte == b'%' {
            let high = bytes.next().and_then(|value| (value as char).to_digit(16));
            let low = bytes.next().and_then(|value| (value as char).to_digit(16));
            match (high, low) {
                (Some(high), Some(low)) => decoded.push((high * 16 + low) as u8),
                _ => return Err(invalid_policy("invalid encoded authentication")),
            }
        } else {
            decoded.push(byte);
        }
    }
    String::from_utf8(decoded).map_err(|_| invalid_policy("invalid encoded authentication"))
}

fn split_address(address: &str) -> Result<(&str, Option<&str>)> {
    if address.starts_with('[') {
        let end = address.find(']').ok_or_else(|| invalid_policy("invalid IPv6 authority"))? + 1;
        let host = &address[..end];
        let suffix = &address[end..];
        if suffix.is_empty() {
            return Ok((host, None));
        }
        return suffix
            .strip_prefix(':')
            .map(|ports| (host, Some(ports)))
            .ok_or_else(|| invalid_policy("invalid IPv6 authority"));
    }
    if let Some((host, ports)) = address.rsplit_once(':') {
        if host.contains(':') {
            return Err(invalid_policy("IPv6 authority requires brackets"));
        }
        Ok((host, Some(ports)))
    } else {
        Ok((address, None))
    }
}

fn interval(query: &HashMap<String, String>, key: &str) -> Result<Option<u64>> {
    query.get(key).map(|raw| parse_duration_millis(raw)).transpose()
}

fn parse_duration_millis(raw: &str) -> Result<u64> {
    if raw.is_empty() || raw.len() > 128 {
        return Err(invalid_policy("invalid interval"));
    }
    if raw.bytes().all(|byte| byte.is_ascii_digit()) {
        return raw
            .parse::<u64>()
            .ok()
            .and_then(|seconds| seconds.checked_mul(1_000))
            .filter(|millis| *millis <= MAX_HOP_INTERVAL_MILLIS)
            .ok_or_else(|| invalid_policy("invalid interval"));
    }
    let mut remaining = raw;
    let mut nanos = 0u128;
    while !remaining.is_empty() {
        let length = remaining.find(|ch: char| !ch.is_ascii_digit() && ch != '.').unwrap_or(remaining.len());
        if length == 0 {
            return Err(invalid_policy("invalid interval"));
        }
        let number = &remaining[..length];
        remaining = &remaining[length..];
        let (unit, multiplier) = [
            ("ms", 1_000_000u128),
            ("us", 1_000),
            ("µs", 1_000),
            ("μs", 1_000),
            ("ns", 1),
            ("h", 3_600_000_000_000),
            ("m", 60_000_000_000),
            ("s", 1_000_000_000),
        ]
        .into_iter()
        .find(|(unit, _)| remaining.starts_with(unit))
        .ok_or_else(|| invalid_policy("invalid interval unit"))?;
        remaining = &remaining[unit.len()..];
        let component =
            decimal_nanos(number, multiplier).ok_or_else(|| invalid_policy("invalid interval precision"))?;
        nanos = nanos
            .checked_add(component)
            .filter(|sum| *sum <= u128::from(MAX_HOP_INTERVAL_MILLIS) * 1_000_000)
            .ok_or_else(|| invalid_policy("interval exceeds supported bounds"))?;
    }
    if !nanos.is_multiple_of(1_000_000) {
        return Err(invalid_policy("interval requires whole milliseconds"));
    }
    u64::try_from(nanos / 1_000_000).map_err(|_| invalid_policy("invalid interval"))
}

fn decimal_nanos(number: &str, multiplier: u128) -> Option<u128> {
    let (whole, fractional) = number.split_once('.').unwrap_or((number, ""));
    if (whole.is_empty() && fractional.is_empty())
        || !whole.bytes().all(|byte| byte.is_ascii_digit())
        || !fractional.bytes().all(|byte| byte.is_ascii_digit())
    {
        return None;
    }
    let whole = if whole.is_empty() { 0 } else { whole.parse::<u128>().ok()? }.checked_mul(multiplier)?;
    let fractional = fractional.trim_end_matches('0');
    if fractional.is_empty() {
        return Some(whole);
    }
    let divisor = 10u128.checked_pow(u32::try_from(fractional.len()).ok()?)?;
    let fraction = fractional.parse::<u128>().ok()?.checked_mul(multiplier)?;
    if !fraction.is_multiple_of(divisor) {
        return None;
    }
    whole.checked_add(fraction / divisor)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn userinfo_preserves_encoded_auth_userpass_and_literal_plus() {
        for (raw, expected) in [
            ("user:pass", "user:pass"),
            ("user%3Apass%2Bword", "user:pass+word"),
            ("p%40ss%3A%2F%3F%23%25word", "p@ss:/?#%word"),
            ("pass+word", "pass+word"),
        ] {
            let config = parse(&format!("hy2://{raw}@localhost:443,444/?hopInterval=5s")).unwrap();
            assert_eq!(config.auth, expected);
        }
        assert!(parse("hy2://%ZZ@localhost:443,444/").is_err());
        assert!(parse("hy2://pass@localhost:443/?mport=443,444").is_err());
    }

    #[test]
    fn parses_exact_duration_units_without_float_rounding() {
        for (input, expected) in [
            ("30", 30_000),
            (".5h", 1_800_000),
            ("5.s", 5_000),
            ("5000ms", 5_000),
            ("5s500000ns500000ns", 5_001),
            ("1m2.005s", 62_005),
            ("5000000us", 5_000),
            ("5000000000ns", 5_000),
            ("9223372036854ms", MAX_HOP_INTERVAL_MILLIS),
        ] {
            assert_eq!(parse_duration_millis(input).unwrap(), expected);
        }
        for input in ["-5s", "+5s", "NaN", "5.0001s", "5s0.5ns0.5ns999999ns", "9223372036855ms", "1..0s"] {
            assert!(parse_duration_millis(input).is_err(), "{input}");
        }
    }
}
