use crate::{ConfigError, DesyncGroup, FM_DUPSID, FM_ORIG, FM_PADENCAP, FM_RAND, FM_RNDSNI};

pub(crate) fn apply_fake_tls_mod_token(
    group: &mut DesyncGroup,
    token: &str,
    arg: &str,
    raw_value: &str,
) -> Result<(), ConfigError> {
    let token = token.trim();
    if token.is_empty() {
        return Err(ConfigError::invalid(arg, Some(raw_value)));
    }
    match token {
        "rand" => group.actions.fake_mod |= FM_RAND,
        "orig" => group.actions.fake_mod |= FM_ORIG,
        "rndsni" => group.actions.fake_mod |= FM_RNDSNI,
        "dupsid" => group.actions.fake_mod |= FM_DUPSID,
        "padencap" => group.actions.fake_mod |= FM_PADENCAP,
        _ => {
            let Some((name, value)) = token.split_once('=') else {
                return Err(ConfigError::invalid(arg, Some(raw_value)));
            };
            match name {
                "m" | "msize" => {
                    group.actions.fake_tls_size =
                        value.parse::<i32>().map_err(|_| ConfigError::invalid(arg, Some(raw_value)))?;
                }
                _ => return Err(ConfigError::invalid(arg, Some(raw_value))),
            }
        }
    }
    Ok(())
}
