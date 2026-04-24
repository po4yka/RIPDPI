package com.poyka.ripdpi.data

interface NativeNetworkSnapshotProvider {
    fun capture(): NativeNetworkSnapshot
}
