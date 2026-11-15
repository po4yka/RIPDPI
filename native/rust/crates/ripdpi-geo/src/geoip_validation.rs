use std::collections::HashSet;

use maxminddb::{MaxMindDbError, Reader, WithinOptions, geoip2};

use crate::mmdb_value::ValueWork;

pub(super) fn validate_snapshot(source: &[u8]) -> Result<(), MaxMindDbError> {
    let reader = Reader::from_source(source)?;
    if !reader.metadata().description.is_empty() {
        reader.verify()?;
    }
    // Description is optional in MMDB. Validate the complete referenced data graph below even
    // when the dependency's stricter metadata verifier cannot accept an empty description.
    let separator = (reader.metadata().node_count as usize)
        .checked_mul(usize::from(reader.metadata().record_size) / 4)
        .and_then(|start| source.get(start..))
        .and_then(|tail| tail.get(..16));
    if !separator.is_some_and(|bytes| bytes.iter().all(|byte| *byte == 0)) {
        return Err(MaxMindDbError::invalid_database("invalid MMDB data section separator"));
    }
    let max_networks = (reader.metadata().node_count as usize).saturating_mul(3);
    let mut remaining_work = source.len().saturating_mul(64);
    let mut offsets = HashSet::new();
    for (index, result) in reader.networks(WithinOptions::default().include_networks_without_data())?.enumerate() {
        if index >= max_networks {
            return Err(MaxMindDbError::invalid_database("MMDB search tree exceeds its node budget"));
        }
        let lookup = result?;
        let Some(offset) = lookup.offset() else { continue };
        if !offsets.insert(offset) {
            continue;
        }
        if let Some(work) = lookup.decode::<ValueWork>()? {
            remaining_work = remaining_work.checked_sub(work.0).ok_or_else(|| MaxMindDbError::ResourceLimit {
                message: "MMDB expanded data exceeds the validation work budget".to_owned(),
                offset: None,
                path: None,
            })?;
        }
        if lookup.decode::<&str>().is_err() {
            lookup.decode::<geoip2::Country>()?;
            lookup.decode::<geoip2::Asn>()?;
        }
    }
    Ok(())
}
