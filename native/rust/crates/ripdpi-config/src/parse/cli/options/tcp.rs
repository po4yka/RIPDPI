use ripdpi_packets::{MH_DMIX, MH_HMIX, MH_METHODEOL, MH_SPACE, MH_UNIXEOL};

use crate::{ConfigError, DesyncMode, EntropyMode, SeqOverlapFakeMode, TcpChainStep, TcpChainStepKind};

use super::super::helpers::{next_value, parse_ttl_byte, parse_wsize, seqovl_step_mut};
use super::super::state::CliState;
use crate::parse::fake_profiles::{
    apply_fake_tls_mod_token, data_from_str, file_or_inline_bytes, parse_http_fake_profile, parse_tls_fake_profile,
    parse_udp_fake_profile,
};
use crate::parse::offsets::{parse_auto_ttl_spec, parse_offset_expr};

pub(super) fn handle(arg: &str, args: &[String], idx: &mut usize, state: &mut CliState) -> Result<bool, ConfigError> {
    match arg {
        "-S" | "--md5sig" => state.group().actions.md5sig = true,
        "-Y" | "--drop-sack" => state.group().actions.drop_sack = true,
        "--window-clamp" => {
            let value = next_value(args, idx, arg)?;
            state.group().actions.window_clamp =
                Some(value.parse::<u32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?);
        }
        "--wsize" => {
            let value = next_value(args, idx, arg)?;
            state.group().actions.wsize = Some(parse_wsize(arg, value)?);
        }
        "--strip-timestamps" => state.group().actions.strip_timestamps = true,
        "--entropy-target" => {
            let value = next_value(args, idx, arg)?;
            let f = value.parse::<f32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
            state.group().actions.entropy_padding_target_permil = Some((f * 1000.0) as u32);
        }
        "--entropy-max-pad" => {
            let value = next_value(args, idx, arg)?;
            state.group().actions.entropy_padding_max =
                value.parse::<u32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
        }
        "--entropy-mode" => {
            let value = next_value(args, idx, arg)?;
            state.group().actions.entropy_mode = match value {
                "popcount" => EntropyMode::Popcount,
                "shannon" => EntropyMode::Shannon,
                "combined" | "auto" => EntropyMode::Combined,
                _ => return Err(ConfigError::invalid(arg, Some(value))),
            };
        }
        "--shannon-target" => {
            let value = next_value(args, idx, arg)?;
            let f = value.parse::<f32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
            if !(0.0..=8.0).contains(&f) {
                return Err(ConfigError::invalid(arg, Some(value)));
            }
            state.group().actions.shannon_entropy_target_permil = Some((f * 1000.0) as u32);
        }
        "-s" | "--split" | "--seqovl" | "-d" | "--disorder" | "-o" | "--oob" | "-q" | "--disoob" | "-f" | "--fake" => {
            let value = next_value(args, idx, arg)?;
            let offset = parse_offset_expr(value)?;
            if arg == "--seqovl" {
                if state.group().actions.tcp_chain.iter().any(|step| step.kind == TcpChainStepKind::SeqOverlap) {
                    return Err(ConfigError::invalid(arg, Some("seqovl already declared for this group")));
                }
                if state.group().actions.tcp_chain.iter().any(|step| !step.kind.is_tls_prelude()) {
                    return Err(ConfigError::invalid(arg, Some("seqovl must be the first tcp send step")));
                }
                let mut step = TcpChainStep::new(TcpChainStepKind::SeqOverlap, offset);
                step.overlap_size = 12;
                step.seqovl_fake_mode = SeqOverlapFakeMode::Profile;
                state.group().actions.tcp_chain.push(step);
            } else {
                let mode = match arg {
                    "-s" | "--split" => DesyncMode::Split,
                    "-d" | "--disorder" => DesyncMode::Disorder,
                    "-o" | "--oob" => DesyncMode::Oob,
                    "-q" | "--disoob" => DesyncMode::Disoob,
                    _ => DesyncMode::Fake,
                };
                if let Some(kind) = TcpChainStepKind::from_mode(mode) {
                    state.group().actions.tcp_chain.push(TcpChainStep::new(kind, offset));
                }
            }
        }
        "--seqovl-overlap" => {
            let value = next_value(args, idx, arg)?;
            let overlap = value.parse::<i32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
            if !(1..=32).contains(&overlap) {
                return Err(ConfigError::invalid(arg, Some(value)));
            }
            seqovl_step_mut(state.group())
                .ok_or_else(|| ConfigError::invalid(arg, Some("missing --seqovl")))?
                .overlap_size = overlap;
        }
        "--seqovl-fake-mode" => {
            let value = next_value(args, idx, arg)?;
            seqovl_step_mut(state.group())
                .ok_or_else(|| ConfigError::invalid(arg, Some("missing --seqovl")))?
                .seqovl_fake_mode = match value.trim().to_ascii_lowercase().as_str() {
                "profile" => SeqOverlapFakeMode::Profile,
                "rand" => SeqOverlapFakeMode::Rand,
                _ => return Err(ConfigError::invalid(arg, Some(value))),
            };
        }
        "-t" | "--ttl" => {
            state.group().actions.ttl = Some(parse_ttl_byte(arg, next_value(args, idx, arg)?)?);
        }
        "--auto-ttl" => {
            state.group().actions.auto_ttl = Some(parse_auto_ttl_spec(next_value(args, idx, arg)?)?);
        }
        "-O" | "--fake-offset" => {
            let value = next_value(args, idx, arg)?;
            let expr = parse_offset_expr(value)?;
            if !expr.supports_fake_offset() {
                return Err(ConfigError::invalid(arg, Some(value)));
            }
            state.group().actions.fake_offset = Some(expr);
        }
        "-Q" | "--fake-tls-mod" => {
            let value = next_value(args, idx, arg)?;
            for token in value.split(',') {
                apply_fake_tls_mod_token(state.group(), token, arg, value)?;
            }
        }
        "-n" | "--fake-sni" => {
            state.group().actions.fake_sni_list.push(next_value(args, idx, arg)?.to_owned());
        }
        "-l" | "--fake-data" => {
            let value = next_value(args, idx, arg)?;
            if state.group().actions.fake_data.is_none() {
                state.group().actions.fake_data = Some(file_or_inline_bytes(value)?);
            }
        }
        "--fake-http-profile" => {
            state.group().actions.http_fake_profile = parse_http_fake_profile(next_value(args, idx, arg)?)?;
        }
        "--fake-tls-profile" => {
            state.group().actions.tls_fake_profile = parse_tls_fake_profile(next_value(args, idx, arg)?)?;
        }
        "--fake-udp-profile" => {
            state.group().actions.udp_fake_profile = parse_udp_fake_profile(next_value(args, idx, arg)?)?;
        }
        "-e" | "--oob-data" => {
            let value = next_value(args, idx, arg)?;
            let bytes = data_from_str(value)?;
            state.group().actions.oob_data = bytes.first().copied();
        }
        "-M" | "--mod-http" => {
            let value = next_value(args, idx, arg)?;
            for token in value.split(',') {
                match token.chars().next() {
                    Some('r') => state.group().actions.mod_http |= MH_SPACE,
                    Some('h') => state.group().actions.mod_http |= MH_HMIX,
                    Some('d') => state.group().actions.mod_http |= MH_DMIX,
                    Some('m') => state.group().actions.mod_http |= MH_METHODEOL,
                    Some('u') => state.group().actions.mod_http |= MH_UNIXEOL,
                    _ => return Err(ConfigError::invalid(arg, Some(value))),
                }
            }
        }
        "-r" | "--tlsrec" => {
            let value = next_value(args, idx, arg)?;
            let expr = parse_offset_expr(value)?;
            if expr.absolute_positive().is_some_and(|pos| pos > u16::MAX as i64) {
                return Err(ConfigError::invalid(arg, Some(value)));
            }
            state.group().actions.tcp_chain.push(TcpChainStep::new(TcpChainStepKind::TlsRec, expr));
        }
        "-m" | "--tlsminor" => {
            let value = next_value(args, idx, arg)?;
            state.group().actions.tlsminor = Some(parse_ttl_byte(arg, value)?);
        }
        _ => return Ok(false),
    }

    Ok(true)
}
