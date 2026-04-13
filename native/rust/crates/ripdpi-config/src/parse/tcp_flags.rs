use crate::{ConfigError, TcpChainStepKind};

pub const TCP_FLAG_FIN: u16 = 0x001;
pub const TCP_FLAG_SYN: u16 = 0x002;
pub const TCP_FLAG_RST: u16 = 0x004;
pub const TCP_FLAG_PSH: u16 = 0x008;
pub const TCP_FLAG_ACK: u16 = 0x010;
pub const TCP_FLAG_URG: u16 = 0x020;
pub const TCP_FLAG_ECE: u16 = 0x040;
pub const TCP_FLAG_CWR: u16 = 0x080;
pub const TCP_FLAG_AE: u16 = 0x100;
pub const TCP_FLAG_R1: u16 = 0x200;
pub const TCP_FLAG_R2: u16 = 0x400;
pub const TCP_FLAG_R3: u16 = 0x800;
pub const TCP_FLAG_MASK_ALL: u16 = 0x0fff;

const NAMED_FLAGS: [(&str, u16); 12] = [
    ("fin", TCP_FLAG_FIN),
    ("syn", TCP_FLAG_SYN),
    ("rst", TCP_FLAG_RST),
    ("psh", TCP_FLAG_PSH),
    ("ack", TCP_FLAG_ACK),
    ("urg", TCP_FLAG_URG),
    ("ece", TCP_FLAG_ECE),
    ("cwr", TCP_FLAG_CWR),
    ("ae", TCP_FLAG_AE),
    ("r1", TCP_FLAG_R1),
    ("r2", TCP_FLAG_R2),
    ("r3", TCP_FLAG_R3),
];

pub fn parse_tcp_flag_mask(spec: &str) -> Result<u16, ConfigError> {
    let trimmed = spec.trim();
    if trimmed.is_empty() {
        return Ok(0);
    }
    if let Some(value) = parse_numeric_mask(trimmed)? {
        return Ok(value);
    }
    let mut mask = 0u16;
    for token in trimmed.split('|') {
        let normalized = token.trim().to_ascii_lowercase();
        if normalized.is_empty() {
            return Err(ConfigError::invalid("tcp-flags", Some(spec)));
        }
        let Some((_, bit)) = NAMED_FLAGS.iter().find(|(name, _)| *name == normalized) else {
            return Err(ConfigError::invalid("tcp-flags", Some(spec)));
        };
        mask |= *bit;
    }
    Ok(mask)
}

pub fn format_tcp_flag_mask(mask: u16) -> String {
    if mask == 0 {
        return String::new();
    }
    NAMED_FLAGS.iter().filter_map(|(name, bit)| ((mask & bit) != 0).then_some(*name)).collect::<Vec<_>>().join("|")
}

pub fn validate_tcp_flag_masks(
    kind: TcpChainStepKind,
    fake_set: Option<u16>,
    fake_unset: Option<u16>,
    orig_set: Option<u16>,
    orig_unset: Option<u16>,
    field_name: &str,
) -> Result<(), ConfigError> {
    let fake_set = normalize_mask(fake_set)?;
    let fake_unset = normalize_mask(fake_unset)?;
    let orig_set = normalize_mask(orig_set)?;
    let orig_unset = normalize_mask(orig_unset)?;

    if (fake_set & fake_unset) != 0 {
        return Err(ConfigError::invalid(field_name, Some("overlapping fake set/unset masks")));
    }
    if (orig_set & orig_unset) != 0 {
        return Err(ConfigError::invalid(field_name, Some("overlapping original set/unset masks")));
    }
    if (fake_set != 0 || fake_unset != 0) && !kind.supports_fake_tcp_flags() {
        return Err(ConfigError::invalid(field_name, Some("fake TCP flag masks are not supported for this step kind")));
    }
    if (orig_set != 0 || orig_unset != 0) && !kind.supports_orig_tcp_flags() {
        return Err(ConfigError::invalid(
            field_name,
            Some("original TCP flag masks are not supported for this step kind"),
        ));
    }
    Ok(())
}

fn normalize_mask(mask: Option<u16>) -> Result<u16, ConfigError> {
    let Some(mask) = mask else {
        return Ok(0);
    };
    if (mask & !TCP_FLAG_MASK_ALL) != 0 {
        return Err(ConfigError::invalid("tcp-flags", Some(mask.to_string())));
    }
    Ok(mask)
}

fn parse_numeric_mask(spec: &str) -> Result<Option<u16>, ConfigError> {
    let value = if let Some(hex) = spec.strip_prefix("0x").or_else(|| spec.strip_prefix("0X")) {
        u16::from_str_radix(hex, 16).ok()
    } else if spec.bytes().all(|byte| byte.is_ascii_digit()) {
        spec.parse::<u16>().ok()
    } else {
        None
    };
    match value {
        Some(value) if (value & !TCP_FLAG_MASK_ALL) == 0 => Ok(Some(value)),
        Some(_) => Err(ConfigError::invalid("tcp-flags", Some(spec))),
        None => Ok(None),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn parse_tcp_flag_mask_supports_named_and_numeric_forms() {
        assert_eq!(parse_tcp_flag_mask("syn|ack|ae").unwrap(), TCP_FLAG_SYN | TCP_FLAG_ACK | TCP_FLAG_AE);
        assert_eq!(parse_tcp_flag_mask("0x112").unwrap(), TCP_FLAG_SYN | TCP_FLAG_ACK | TCP_FLAG_AE);
        assert_eq!(parse_tcp_flag_mask("274").unwrap(), TCP_FLAG_SYN | TCP_FLAG_ACK | TCP_FLAG_AE);
    }

    #[test]
    fn format_tcp_flag_mask_emits_canonical_order() {
        assert_eq!(format_tcp_flag_mask(TCP_FLAG_ACK | TCP_FLAG_SYN | TCP_FLAG_AE), "syn|ack|ae");
    }

    #[test]
    fn validate_tcp_flag_masks_rejects_invalid_placements_and_overlap() {
        assert!(validate_tcp_flag_masks(
            TcpChainStepKind::Split,
            Some(TCP_FLAG_SYN),
            None,
            None,
            None,
            "tcpChainSteps"
        )
        .is_err());
        assert!(validate_tcp_flag_masks(TcpChainStepKind::Oob, None, None, Some(TCP_FLAG_ACK), None, "tcpChainSteps")
            .is_err());
        assert!(validate_tcp_flag_masks(
            TcpChainStepKind::Fake,
            Some(TCP_FLAG_SYN),
            Some(TCP_FLAG_SYN),
            None,
            None,
            "tcpChainSteps"
        )
        .is_err());
    }
}
