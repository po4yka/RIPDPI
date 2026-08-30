#![forbid(unsafe_code)]

//! Constant-rate, framed traffic shaping for cooperative stream peers.

mod profile;
mod stats;
mod stream;

pub use profile::{OpusVoip, TrafficShapeProfile, WebRtcVideo};
pub use stats::{TrafficShapeStats, TrafficShapeStatsSnapshot};
pub use stream::{ShapedStream, Shaper};
