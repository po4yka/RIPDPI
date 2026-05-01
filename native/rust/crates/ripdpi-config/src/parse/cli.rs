use crate::{ConfigError, ParseResult, StartupEnv};

mod helpers;
mod options;
mod state;
#[cfg(test)]
mod tests;

pub use helpers::{parse_hosts_spec, parse_ipset_spec};

use helpers::split_plugin_options;
use options::{handle_option, OptionOutcome};
use state::CliState;

pub fn parse_cli(args: &[String], startup: &StartupEnv) -> Result<ParseResult, ConfigError> {
    let mut state = CliState::new(startup);
    let effective_args =
        if let Some(options) = &startup.ss_plugin_options { split_plugin_options(options) } else { args.to_vec() };

    let mut idx = 0usize;
    while idx < effective_args.len() {
        let arg = &effective_args[idx];
        match handle_option(arg, &effective_args, &mut idx, &mut state)? {
            OptionOutcome::Handled => {}
            OptionOutcome::Help => return Ok(ParseResult::Help),
            OptionOutcome::Version => return Ok(ParseResult::Version),
            OptionOutcome::Unknown => return Err(ConfigError::invalid(arg, Option::<String>::None)),
        }
        idx += 1;
    }

    Ok(ParseResult::Run(Box::new(state.finish()?)))
}
