package com.poyka.ripdpi.services

import okhttp3.Call
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.ForwardingSource
import okio.buffer
import java.io.IOException

private const val ErrorResponseMaxBytes = 64L * 1024L

/** Bounds decoded response bytes before Retrofit can buffer success or error bodies. */
internal fun Response.withBoundedBody(
    call: Call,
    maxBytes: Long,
    label: String,
): Response {
    if (request.method == "HEAD") return this
    val limit = if (isSuccessful) maxBytes else minOf(maxBytes, ErrorResponseMaxBytes)
    val originalBody = body

    fun overflow(kind: String): Nothing {
        // Cancel before close so HTTP/1.1 does not drain the remaining oversized body for reuse.
        call.cancel()
        originalBody.close()
        throw IOException("$label $kind body exceeds byte limit $limit (HTTP $code)")
    }

    if (originalBody.contentLength() > limit) overflow("declared")
    val boundedSource =
        object : ForwardingSource(originalBody.source()) {
            private var total = 0L

            override fun read(
                sink: Buffer,
                byteCount: Long,
            ): Long {
                val read = super.read(sink, minOf(byteCount, limit - total + 1L))
                if (read > 0L) {
                    total += read
                    if (total > limit) overflow("streamed")
                }
                return read
            }
        }.buffer()
    return newBuilder()
        .body(
            object : ResponseBody() {
                override fun contentType() = originalBody.contentType()

                override fun contentLength() = originalBody.contentLength()

                override fun source() = boundedSource
            },
        ).build()
}
