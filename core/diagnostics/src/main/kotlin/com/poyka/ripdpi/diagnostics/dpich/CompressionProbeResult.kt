package com.poyka.ripdpi.diagnostics.dpich

enum class CompressionCodec(
    val acceptEncoding: String,
) {
    GZIP("gzip"),
    DEFLATE("deflate"),
    BROTLI("br"),
    ZSTD("zstd"),
}

enum class CompressionProbeVerdict {
    OK,
    NOT_SUPPORTED,
    EOF_BEFORE_MIN,
    TIMEOUT,
    CONN_ERR,
    INTERNAL_ERR,
}

data class CompressionProbeResult(
    val verdict: CompressionProbeVerdict,
    val httpStatus: Int? = null,
    val compressedBytes: Long? = null,
    val decompressedBytes: Long? = null,
    val error: String? = null,
)
