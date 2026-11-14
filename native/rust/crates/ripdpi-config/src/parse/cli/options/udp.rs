mod quic_fake_version;
mod quic_sni_split;

use crate::{ConfigError, MAX_FAKE_BURST_COUNT, UdpChainStep, UdpChainStepKind};

use super::super::helpers::next_value;
use super::super::state::CliState;
use crate::parse::fake_profiles::{normalize_quic_fake_host, parse_quic_fake_profile};

pub(super) fn handle(arg: &str, args: &[String], idx: &mut usize, state: &mut CliState) -> Result<bool, ConfigError> {
    match arg {
        "--quic-sni-split" => quic_sni_split::push_step(state, arg)?,
        "--quic-low-port" => state.group().actions.quic_bind_low_port = true,
        "--quic-dummy-prepend" => {
            state.group().actions.udp_chain.push(UdpChainStep::new(UdpChainStepKind::DummyPrepend, 1));
        }
        "--quic-fake-version" => {
            quic_fake_version::apply(state.group(), next_value(args, idx, arg)?)?;
        }
        "--quic-migrate" => state.group().actions.quic_migrate_after_handshake = true,
        "-a" | "--udp-fake" => {
            let value = next_value(args, idx, arg)?;
            let count = value.parse::<i32>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
            if !(0..=MAX_FAKE_BURST_COUNT).contains(&count) {
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
