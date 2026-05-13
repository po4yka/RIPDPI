use crate::ConfigError;

use super::super::super::helpers::next_value;
use super::super::super::state::CliState;

pub(super) fn handle(arg: &str, args: &[String], idx: &mut usize, state: &mut CliState) -> Result<bool, ConfigError> {
    match arg {
        "--host-autolearn" => {
            state.config.host_autolearn.enabled = true;
        }
        "--host-autolearn-penalty-ttl" => {
            let value = next_value(args, idx, arg)?;
            let ttl = value.parse::<i64>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
            if ttl <= 0 {
                return Err(ConfigError::invalid(arg, Some(value)));
            }
            state.config.host_autolearn.enabled = true;
            state.config.host_autolearn.penalty_ttl_secs = ttl;
        }
        "--host-autolearn-max-hosts" => {
            let value = next_value(args, idx, arg)?;
            let max_hosts = value.parse::<usize>().map_err(|_| ConfigError::invalid(arg, Some(value)))?;
            if max_hosts == 0 {
                return Err(ConfigError::invalid(arg, Some(value)));
            }
            state.config.host_autolearn.enabled = true;
            state.config.host_autolearn.max_hosts = max_hosts;
        }
        "--host-autolearn-file" => {
            let value = next_value(args, idx, arg)?;
            if value.trim().is_empty() {
                return Err(ConfigError::invalid(arg, Some(value)));
            }
            state.config.host_autolearn.enabled = true;
            state.config.host_autolearn.store_path = Some(value.to_owned());
        }
        _ => return Ok(false),
    }
    Ok(true)
}
