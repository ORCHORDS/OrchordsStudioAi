package com.orchords.ai.provider

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class ProviderIngressHttpTest {
    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.close()
    }

    @Test
    fun `chunked response without content length is bounded by observed bytes`() {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "application/json")
                .chunkedBody("abcdef", maxChunkSize = 2)
                .build(),
        )

        val response = client(jsonLimit = 5).newCall(request("/chunked")).execute()
        val error = assertThrows(ProviderPayloadTooLargeException::class.java) {
            response.use { it.body.string() }
        }

        assertEquals(5L, error.limitBytes)
        assertEquals(6L, error.observedBytes)
    }

    @Test
    fun `transparent gzip is bounded by decompressed bytes`() {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "application/json")
                .addHeader("Content-Encoding", "gzip")
                .body(Buffer().write(gzip("abcdef")))
                .build(),
        )

        val response = client(jsonLimit = 5).newCall(request("/gzip")).execute()
        assertEquals(-1L, response.body.contentLength())
        val error = assertThrows(ProviderPayloadTooLargeException::class.java) {
            response.use { it.body.string() }
        }

        assertEquals(5L, error.limitBytes)
        assertEquals(6L, error.observedBytes)
    }

    @Test
    fun `error response uses smaller error budget before materialization`() {
        server.enqueue(
            MockResponse.Builder()
                .code(500)
                .addHeader("Content-Type", "application/json")
                .body("abcdef")
                .build(),
        )

        val error = assertThrows(ProviderPayloadTooLargeException::class.java) {
            client(jsonLimit = 100, errorLimit = 5).newCall(request("/error")).execute()
        }

        assertEquals(5L, error.limitBytes)
        assertEquals(6L, error.observedBytes)
    }

    @Test
    fun `event stream total body is bounded independently from ordinary json`() {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "text/event-stream")
                .chunkedBody("abcdef", maxChunkSize = 2)
                .build(),
        )

        val response = client(jsonLimit = 100, streamLimit = 5).newCall(request("/stream")).execute()
        val error = assertThrows(ProviderPayloadTooLargeException::class.java) {
            response.use { it.body.string() }
        }

        assertEquals(5L, error.limitBytes)
        assertEquals(6L, error.observedBytes)
    }

    @Test
    fun `exact real http json budget remains readable`() {
        server.enqueue(
            MockResponse.Builder()
                .addHeader("Content-Type", "application/json")
                .body("hello")
                .build(),
        )

        client(jsonLimit = 5).newCall(request("/exact")).execute().use { response ->
            assertEquals("hello", response.body.string())
        }
    }

    private fun client(
        jsonLimit: Long,
        errorLimit: Long = 32,
        streamLimit: Long = 32,
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(
            ProviderIngressInterceptor(
                jsonBodyLimitBytes = jsonLimit,
                errorBodyLimitBytes = errorLimit,
                streamBodyLimitBytes = streamLimit,
            ),
        )
        .build()

    private fun request(path: String): Request = Request.Builder()
        .url(server.url(path))
        .build()

    private fun gzip(value: String): ByteArray {
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(value.toByteArray(Charsets.UTF_8)) }
        return output.toByteArray()
    }
}
