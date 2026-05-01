use std::sync::Arc;

use metrics::{Counter, Gauge, Histogram, Key, KeyName, Metadata, Recorder, SharedString, Unit};

use crate::recorder::global_install::recorder;
use crate::recorder::histogram_handle::HistogramHandle;
use crate::recorder::state::InMemoryRecorder;

/// Zero-size proxy that delegates to the `OnceLock`-stored recorder.
pub(crate) struct RecorderProxy;

impl Recorder for RecorderProxy {
    fn describe_counter(&self, _key: KeyName, _unit: Option<Unit>, _description: SharedString) {}

    fn describe_gauge(&self, _key: KeyName, _unit: Option<Unit>, _description: SharedString) {}

    fn describe_histogram(&self, _key: KeyName, _unit: Option<Unit>, _description: SharedString) {}

    fn register_counter(&self, key: &Key, _metadata: &Metadata<'_>) -> Counter {
        let Some(rec) = recorder() else {
            return Counter::noop();
        };

        let name = InMemoryRecorder::key_name(key);
        match rec.register_counter(name) {
            Some(counter) => Counter::from_arc(counter),
            None => Counter::noop(),
        }
    }

    fn register_gauge(&self, key: &Key, _metadata: &Metadata<'_>) -> Gauge {
        let Some(rec) = recorder() else {
            return Gauge::noop();
        };

        let name = InMemoryRecorder::key_name(key);
        match rec.register_gauge(name) {
            Some(gauge) => Gauge::from_arc(gauge),
            None => Gauge::noop(),
        }
    }

    fn register_histogram(&self, key: &Key, _metadata: &Metadata<'_>) -> Histogram {
        let Some(rec) = recorder() else {
            return Histogram::noop();
        };

        let name = InMemoryRecorder::key_name(key);
        match rec.register_histogram(name) {
            Some(histogram) => Histogram::from_arc(Arc::new(HistogramHandle(histogram))),
            None => Histogram::noop(),
        }
    }
}
