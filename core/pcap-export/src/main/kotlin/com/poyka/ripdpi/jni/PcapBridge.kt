package com.poyka.ripdpi.jni

/**
 * JNI bindings for the libpcap capture pipeline exposed by
 * libripdpi-tunnel.so (Rust crate ripdpi-tunnel-android).
 *
 * Native-side contract:
 *  - jniPcapStart returns positive capture-set id on success, 0 on
 *    failure. The capture set is bound to the tunnel session handle;
 *    jniDestroy on the session retires the capture-set automatically.
 *  - jniPcapStop returns a typed JSON completion response: whether a
 *    capture was active, only fully finalized capture files, and an
 *    optional stable writer-failure code. Returns null if the native
 *    call panics; callers surface this as a terminal failure.
 *  - jniPcapListCaptures returns a JSON array of best-effort metadata
 *    (filesystem size only; packet count NOT computed - call
 *    PcapReader to count packets per file). Returns null if the
 *    native call panics; callers treat null as an empty result.
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
    external fun jniPcapStart(
        sessionHandle: Long,
        captureDir: String,
        maxFileBytes: Long,
        maxFiles: Int,
    ): Long

    external fun jniPcapStop(sessionHandle: Long): String?

    external fun jniPcapListCaptures(captureDir: String): String?

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
