use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use crate::LatencyPercentiles;
use crate::recorder::global_install::recorder;

/// Serializable snapshot of all recorded metrics at a point in time.
#[derive(Debug, Clone, Default, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RecorderSnapshot {
    pub counters: BTreeMap<String, u64>,
    pub gauges: BTreeMap<String, u64>,
    pub histograms: BTreeMap<String, LatencyPercentiles>,
    pub captured_at: u64,
}

/// Returns a snapshot of all recorded metrics.
///
/// Safe to call from any thread. Returns `None` if the recorder has not
/// been installed.
pub fn snapshot() -> Option<RecorderSnapshot> {
    let rec = recorder()?;
    let captured_at = captured_at_ms();

    Some(RecorderSnapshot {
        counters: rec.counter_values()?.into_iter().collect(),
        gauges: rec.gauge_values()?.into_iter().collect(),
        histograms: rec.histogram_values()?.into_iter().collect(),
        captured_at,
    })
}

fn captured_at_ms() -> u64 {
    std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map_or(0, |d| d.as_millis() as u64)
}
