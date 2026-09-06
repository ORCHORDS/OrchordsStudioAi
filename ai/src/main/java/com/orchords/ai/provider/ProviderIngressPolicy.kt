package com.orchords.ai.provider

import okhttp3.Interceptor
import okhttp3.MediaType
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer

internal const val MAX_PROVIDER_JSON_BODY_BYTES: Long = 32L * 1024L * 1024L
internal const val MAX_PROVIDER_ERROR_BODY_BYTES: Long = 256L * 1024L
internal const val MAX_PROVIDER_SSE_EVENT_BYTES: Long = 8L * 1024L * 1024L
private const val MAX_PROVIDER_STREAM_BODY_BYTES: Long = 256L * 1024L * 1024L

internal class ProviderPayloadTooLargeException(
    val limitBytes: Long,
    val observedBytes: Long?,
    message: String,
) : IllegalStateException(message)

/**
 * Bounds bytes exposed by a provider ResponseBody even when Content-Length is absent, incorrect,
 * chunked, or removed by transparent content decoding.
 */
internal fun ResponseBody.withProviderByteLimit(maxBytes: Long): ResponseBody {
    require(maxBytes > 0L) { "maxBytes must be positive" }
    val declared = contentLength()
    if (declared > maxBytes) {
        close()
        throw ProviderPayloadTooLargeException(
            limitBytes = maxBytes,
            observedBytes = declared,
            message = "Provider response exceeds the configured byte limit",
        )
    }
    return BoundedProviderResponseBody(this, maxBytes)
}

/** Reject one SSE event before provider JSON decoding or downstream retention. */
internal fun requireProviderSseEventWithinLimit(
    data: String,
    maxBytes: Long = MAX_PROVIDER_SSE_EVENT_BYTES,
) {
    require(maxBytes > 0L) { "maxBytes must be positive" }
    var bytes = 0L
    var index = 0
    while (index < data.length) {
        val ch = data[index]
        bytes += when {
            ch.code <= 0x7f -> 1L
            ch.code <= 0x7ff -> 2L
            ch.isHighSurrogate() && index + 1 < data.length && data[index + 1].isLowSurrogate() -> {
                index += 1
                4L
            }
            else -> 3L
        }
        if (bytes > maxBytes) {
            throw ProviderPayloadTooLargeException(
                limitBytes = maxBytes,
                observedBytes = bytes,
                message = "Provider SSE event exceeds the configured byte limit",
            )
        }
        index += 1
    }
}

internal class ProviderIngressInterceptor(
    private val jsonBodyLimitBytes: Long = MAX_PROVIDER_JSON_BODY_BYTES,
    private val errorBodyLimitBytes: Long = MAX_PROVIDER_ERROR_BODY_BYTES,
    private val streamBodyLimitBytes: Long = MAX_PROVIDER_STREAM_BODY_BYTES,
) : Interceptor {
    init {
        require(jsonBodyLimitBytes > 0L) { "jsonBodyLimitBytes must be positive" }
        require(errorBodyLimitBytes > 0L) { "errorBodyLimitBytes must be positive" }
        require(streamBodyLimitBytes > 0L) { "streamBodyLimitBytes must be positive" }
    }

    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        val body = response.body ?: return response
        val maxBytes = when {
            !response.isSuccessful -> errorBodyLimitBytes
            body.contentType()?.isEventStream() == true -> streamBodyLimitBytes
            else -> jsonBodyLimitBytes
        }
        return try {
            response.newBuilder()
                .body(body.withProviderByteLimit(maxBytes))
                .build()
        } catch (error: Throwable) {
            response.close()
            throw error
        }
    }
}

private class BoundedProviderResponseBody(
    private val delegate: ResponseBody,
    private val maxBytes: Long,
) : ResponseBody() {
    override fun contentType(): MediaType? = delegate.contentType()
    override fun contentLength(): Long = delegate.contentLength()

    override fun source(): BufferedSource {
        val counted = object : ForwardingSource(delegate.source()) {
            private var observedBytes = 0L

            override fun read(sink: Buffer, byteCount: Long): Long {
                val remaining = maxBytes - observedBytes
                if (remaining < 0L) {
                    throw tooLarge(observedBytes)
                }
                val read = super.read(sink, minOf(byteCount, remaining + 1L))
                if (read > 0L) {
                    observedBytes += read
                    if (observedBytes > maxBytes) {
                        throw tooLarge(observedBytes)
                    }
                }
                return read
            }

            private fun tooLarge(observed: Long) = ProviderPayloadTooLargeException(
                limitBytes = maxBytes,
                observedBytes = observed,
                message = "Provider response exceeds the configured byte limit",
            )
        }
        return counted.buffer()
    }
}

private fun MediaType.isEventStream(): Boolean =
    type.equals("text", ignoreCase = true) && subtype.equals("event-stream", ignoreCase = true)
