use std::io::{self, Write};

use ripdpi_config::{RuntimeConfig, dump_cache_entries};

use crate::runtime_policy::types::CacheRecord;

pub(super) fn dump_stdout_cache_groups<W: Write>(
    records: &[CacheRecord],
    config: &RuntimeConfig,
    mut writer: W,
) -> io::Result<()> {
    for (group_index, group) in config.groups.iter().enumerate() {
        if group.policy.cache_file.as_deref() != Some("-") {
            continue;
        }
        let entries: Vec<_> = records
            .iter()
            .filter(|record| record.group_index == group_index)
            .map(|record| record.entry.clone())
            .collect();
        writer.write_all(dump_cache_entries(&entries).as_bytes())?;
    }
    writer.flush()
}
