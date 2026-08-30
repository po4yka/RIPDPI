package com.poyka.ripdpi.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class TrafficShapeConfig(
    val profile: TrafficShapeProfile = TrafficShapeProfile.Off,
) {
    val enabled: Boolean
        get() = profile != TrafficShapeProfile.Off
}

@Serializable
enum class TrafficShapeProfile(
    val minimumFrameBytes: Int,
    val maximumFrameBytes: Int,
    val intervalMillis: Int,
) {
    @SerialName("off")
    Off(
        minimumFrameBytes = 0,
        maximumFrameBytes = 0,
        intervalMillis = 0,
    ),

    @SerialName("opus_voip")
    OpusVoip(
        minimumFrameBytes = 200,
        maximumFrameBytes = 200,
        intervalMillis = 20,
    ),

    @SerialName("webrtc_video")
    WebRtcVideo(
        minimumFrameBytes = 600,
        maximumFrameBytes = 1_200,
        intervalMillis = 10,
    ),
}
