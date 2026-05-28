package com.poyka.ripdpi.activities

import com.poyka.ripdpi.data.ConnectionQualitySnapshot
import com.poyka.ripdpi.data.NativeRuntimeSnapshot

/**
 * Projects [ConnectionQualitySnapshot] out of [NativeRuntimeSnapshot]
 * for the HomeScreen DegradationStrip. Lives in its own file so the
 * file-feature-spread baseline on [MainStateResolvers] / [MainViewModel]
 * stays at the architecture-health baseline value.
 */
internal fun resolveConnectionQuality(snapshot: NativeRuntimeSnapshot?): ConnectionQualitySnapshot? =
    snapshot?.connectionQuality
