use crate::{AUTO_NOPOST, AUTO_RECONN, AUTO_SORT, ConfigError};

use super::super::super::helpers::{next_value, parse_auto_detect_token};
use super::super::super::state::CliState;

pub(super) fn handle_auto_mode(
    arg: &str,
    args: &[String],
    idx: &mut usize,
    state: &mut CliState,
) -> Result<(), ConfigError> {
    let value = next_value(args, idx, arg)?;
    for token in value.split(',') {
        match token.chars().next() {
            Some('0' | '2') => {
                state.config.adaptive.auto_level |= AUTO_NOPOST;
                if token.starts_with('2') {
                    state.config.adaptive.auto_level |= AUTO_SORT;
                }
            }
            Some('1') => {}
            Some('3' | 's') => state.config.adaptive.auto_level |= AUTO_SORT,
            Some('r') => state.config.adaptive.auto_level = 0,
            _ => return Err(ConfigError::invalid(arg, Some(value))),
        }
    }
    Ok(())
}

pub(super) fn handle_auto(
    arg: &str,
    args: &[String],
    idx: &mut usize,
    state: &mut CliState,
) -> Result<(), ConfigError> {
    let value = next_value(args, idx, arg)?;
    if current_group_is_unlimited(state) {
        state.all_limited = false;
    }
    state.add_current_group()?;
    for token in value.split(',') {
        if token.starts_with("p=") {
            set_previous_priority(state, token)?;
            continue;
        }
        match parse_auto_detect_token(token) {
            Some(bits) => state.group().matches.detect |= bits,
            None => return Err(ConfigError::invalid("--auto", Some(token))),
        }
    }
    if state.group().matches.detect != 0 {
        state.config.adaptive.auto_level |= AUTO_RECONN;
    }
    Ok(())
}

fn current_group_is_unlimited(state: &CliState) -> bool {
    let Some(current) = state.config.groups.get(state.current_group_index) else {
        return false;
    };
    current.matches.filters.host_filters_empty()
        && current.matches.proto == 0
        && current.matches.port_filter.is_none()
        && current.matches.detect == 0
        && current.matches.filters.ip_filters_empty()
}

fn set_previous_priority(state: &mut CliState, token: &str) -> Result<(), ConfigError> {
    let (_, pri) = token.split_once('=').ok_or_else(|| ConfigError::invalid("--auto", Some(token)))?;
    let pri = pri.parse::<f32>().map_err(|_| ConfigError::invalid("--auto", Some(token)))?;
    if let Some(prev) = state.config.groups.get_mut(state.current_group_index - 1) {
        prev.policy.pri = pri as i32;
    }
    Ok(())
}
