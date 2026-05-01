use crate::{ConfigError, UdpChainStep, UdpChainStepKind};

use super::super::helpers::next_value;
use super::super::state::CliState;
use crate::parse::fake_profiles::{normalize_quic_fake_host, parse_quic_fake_profile};

pub(super) fn handle(arg: &str, args: &[String], idx: &mut usize, state: &mut CliState) -> Result<bool, ConfigError> {
    match arg {
        "--quic-sni-split" => {
            state.group().actions.udp_chain.push(UdpChainStep::new(UdpChainStepKind::QuicSniSplit, 1));
        }
        "--quic-low-port" => state.group().actions.quic_bind_low_port = true,
        "--quic-dummy-prepend" => {
            state.group().actions.udp_chain.push(UdpChainStep::new(UdpChainStepKind::DummyPrepend, 1));
        }
        "--quic-fake-version" => {
            let value = next_value(args, idx, arg)?;
            let version = u32::from_str_radix(value.trim_start_matches("0x").trim_start_matches("0X"), 16)
                .map_err(|_| ConfigError::invalid(arg, Some(value)))?;
            state.group().actions.quic_fake_version = version;
            state.group().actions.udp_chain.push(UdpChainStep::new(UdpChainStepKind::QuicFakeVersion, 1));
        }
        "--quic-migrate" => state.group().actions.quic_migrate_after_handshake = true,
        "-a" | "--udp-fake" => {
            let value = next_value(args, idx, arg)?;
            let count = value.parse::<i32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
            if count < 0 {
                return Err(ConfigError::invalid(arg, Some(value)));
            }
            if count > 0 {
                state.group().actions.udp_chain.push(UdpChainStep::new(UdpChainStepKind::FakeBurst, count));
            }
        }
        "--fake-quic-profile" => {
            state.group().actions.quic_fake_profile = parse_quic_fake_profile(next_value(args, idx, arg)?)?;
        }
        "--fake-quic-host" => {
            state.group().actions.quic_fake_host = Some(normalize_quic_fake_host(next_value(args, idx, arg)?)?);
        }
        _ => return Ok(false),
    }

    Ok(true)
}
