mod filters;
mod runtime;
mod tcp;
mod udp;

use crate::ConfigError;

use super::state::CliState;

pub(super) enum OptionOutcome {
    Handled,
    Help,
    Version,
    Unknown,
}

pub(super) fn handle_option(
    arg: &str,
    args: &[String],
    idx: &mut usize,
    state: &mut CliState,
) -> Result<OptionOutcome, ConfigError> {
    match arg {
        "-h" | "--help" => return Ok(OptionOutcome::Help),
        "-v" | "--version" => return Ok(OptionOutcome::Version),
        _ => {}
    }

    if runtime::handle(arg, args, idx, state)? {
        return Ok(OptionOutcome::Handled);
    }
    if filters::handle(arg, args, idx, state)? {
        return Ok(OptionOutcome::Handled);
    }
    if tcp::handle(arg, args, idx, state)? {
        return Ok(OptionOutcome::Handled);
    }
    if udp::handle(arg, args, idx, state)? {
        return Ok(OptionOutcome::Handled);
    }

    Ok(OptionOutcome::Unknown)
}
