use crate::LatencyHistogram;

pub(crate) struct HistogramHandle(pub(crate) LatencyHistogram);

impl metrics::HistogramFn for HistogramHandle {
    fn record(&self, value: f64) {
        // metrics convention: durations in seconds.
        // LatencyHistogram stores milliseconds.
        let ms = (value * 1000.0) as u64;
        self.0.record(ms);
    }
}
