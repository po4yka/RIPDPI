package com.poyka.ripdpi.jni

import com.poyka.ripdpi.core.Tun2SocksNativeLoader

/**
 * JNI bindings for the libpcap capture pipeline exposed by
 * libripdpi-tunnel.so (Rust crate ripdpi-tunnel-android).
 *
 * Native-side contract:
 *  - jniPcapStart returns positive capture-set id on success, 0 on
 *    failure. The capture set is bound to the tunnel session handle;
 *    jniDestroy on the session retires the capture-set automatically.
 *  - jniPcapStop returns a typed JSON completion response. With
 *    finishedOnly set, pending=true leaves a running writer untouched.
 *    A null response means the JNI call failed.
 *  - jniPcapListCaptures returns finalized files and saved drop counts.
 *    Packet count is computed by the packet reader. A missing store
 *    returns an empty array. With cleanupOnly, the call performs
 *    retention and returns an empty array instead of listing files.
 *    A null response means the storage operation failed.
 *  - jniPcapRedactToFile takes a SAF-provided fd that has had
 *    ParcelFileDescriptor.detachFd() called - NOT .fd() (which peeks).
 *    The fd is closed by Rust on completion. Returns bytes-written.
 *
 * Symbols expected: Java_com_poyka_ripdpi_jni_PcapBridge_jniPcap*
 * (matches the #[unsafe(no_mangle)] exports in
 * native/rust/crates/ripdpi-tunnel-android/src/entry.rs).
 *
 * Visibility: `internal` so only the `:core:pcap-export` module can
 * call directly. UI / engine layers must go through
 * [com.poyka.ripdpi.pcap.PcapController] which enforces the SAF
 * detachFd ownership contract at its single call site.
 */
internal object PcapBridge {
    init {
        Tun2SocksNativeLoader.ensureLoaded()
    }

    external fun jniPcapStart(
        sessionHandle: Long,
        captureDir: String,
        maxFileBytes: Long,
        maxFiles: Int,
    ): Long

    external fun jniPcapStop(
        sessionHandle: Long,
        finishedOnly: Boolean,
    ): String?

    external fun jniPcapListCaptures(
        captureDir: String,
        cleanupOnly: Boolean,
    ): String?

    /**
     * source  - absolute filesystem path to the .pcap to redact
     * destFd  - SAF detachFd() integer; Rust takes ownership and closes
     * returns bytes written to destFd, 0 on failure
     *
     * MUST be called only from [com.poyka.ripdpi.pcap.PcapController.redactToUri]
     * so the ParcelFileDescriptor.detachFd() ownership contract holds.
     */
    external fun jniPcapRedactToFile(
        source: String,
        destFd: Int,
    ): Long
}
