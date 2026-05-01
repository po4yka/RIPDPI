use crate::BuildError;

pub(crate) const IP_FRAGMENT_ALIGNMENT_BYTES: usize = 8;

pub(crate) fn resolve_effective_split(requested: usize, transport_len: usize) -> Result<usize, BuildError> {
    let effective = requested
        .checked_add(IP_FRAGMENT_ALIGNMENT_BYTES - 1)
        .map(|value| (value / IP_FRAGMENT_ALIGNMENT_BYTES) * IP_FRAGMENT_ALIGNMENT_BYTES)
        .ok_or(BuildError::ValueTooLarge)?;
    if effective == 0 || effective >= transport_len {
        return Err(BuildError::InvalidSplit { requested, effective, transport_len });
    }
    Ok(effective)
}
