use std::time::Duration;

const OPUS_VOIP_FRAME_SIZES: &[usize] = &[200];
const WEBRTC_VIDEO_FRAME_SIZES: &[usize] = &[600, 900, 1_200, 900];

/// The built-in traffic-shaping profiles shared by both cooperative peers.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TrafficShapeProfile {
    /// A 200-byte frame every 20 milliseconds.
    OpusVoip,
    /// A deterministic 600-1,200-byte cycle every 10 milliseconds.
    WebRtcVideo,
}

/// The fixed-size Opus-like profile.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct OpusVoip;

/// The bounded, variable-size WebRTC-video-like profile.
#[derive(Debug, Default, Clone, Copy, PartialEq, Eq)]
pub struct WebRtcVideo;

impl TrafficShapeProfile {
    /// The smallest encoded frame emitted by this profile.
    #[must_use]
    pub const fn minimum_frame_bytes(self) -> usize {
        match self {
            Self::OpusVoip => 200,
            Self::WebRtcVideo => 600,
        }
    }

    /// The largest encoded frame emitted by this profile.
    #[must_use]
    pub const fn maximum_frame_bytes(self) -> usize {
        match self {
            Self::OpusVoip => 200,
            Self::WebRtcVideo => 1_200,
        }
    }

    /// The monotonic interval between frames.
    #[must_use]
    pub const fn tick_interval(self) -> Duration {
        match self {
            Self::OpusVoip => Duration::from_millis(20),
            Self::WebRtcVideo => Duration::from_millis(10),
        }
    }

    pub(crate) const fn frame_sizes(self) -> &'static [usize] {
        match self {
            Self::OpusVoip => OPUS_VOIP_FRAME_SIZES,
            Self::WebRtcVideo => WEBRTC_VIDEO_FRAME_SIZES,
        }
    }
}
