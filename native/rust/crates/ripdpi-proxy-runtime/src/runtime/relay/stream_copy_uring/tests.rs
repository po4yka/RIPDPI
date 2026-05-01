use std::time::{Duration, Instant};

use super::freeze_detector::FreezeDetector;

#[test]
fn freeze_detector_stays_inactive_until_data_is_observed() {
    let mut detector = FreezeDetector::new(1, 10, 1);

    assert!(!detector.check(Instant::now() + Duration::from_millis(2)));
}

#[test]
fn freeze_detector_trips_after_configured_stalls() {
    let mut detector = FreezeDetector::new(1, 10, 2);
    detector.record_bytes(1);

    assert!(!detector.check(Instant::now() + Duration::from_millis(2)));
    assert!(detector.check(Instant::now() + Duration::from_millis(4)));
}
