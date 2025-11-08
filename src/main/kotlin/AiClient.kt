package com.taskconvertai

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.client.request.forms.*
import io.ktor.http.*
import io.ktor.http.content.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.client.plugins.*
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

interface AiClient {
    suspend fun transcribeAudio(audioBytes: ByteArray): List<SpeakerUtterance>
    suspend fun analyzeText(text: String): MeetingSummary
}

@Serializable
data class ExternalTranscriptionSegment(
    val speaker: String? = null,
    val text: String,
    val start: Double? = null,
    val end: Double? = null
)

@Serializable
data class ExternalTranscriptionResponse(
    val segments: List<ExternalTranscriptionSegment>
)

@Serializable
data class ExternalAnalysisTask(
    val tasks: List<TaskItem> = emptyList(),
    val summary: String
)

class HttpAiClient(baseUrl: String, timeoutMs: Long): AiClient {
    private val jsonCfg = Json { ignoreUnknownKeys = true }
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(jsonCfg) }
        install(Logging) { level = LogLevel.INFO }
        install(HttpTimeout) {
            requestTimeoutMillis = timeoutMs
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = timeoutMs + 10_000
        }
    }
    private val transcribeUrl = "$baseUrl/transcribe"
    private val analyzeUrl = "$baseUrl/analyze"

    private suspend fun <T> withRetry(maxRetries: Int = 3, baseDelayMs: Long = 1000, block: suspend () -> T): T {
        var attempt = 0
        var lastErr: Throwable? = null
        while (attempt < maxRetries) {
            try {
                return block()
            } catch (e: HttpRequestTimeoutException) {
                lastErr = e
            } catch (e: Exception) {
                // если не таймаут — пробуем 1 дополнительный ретрай и выходим
                lastErr = e
                if (e !is java.io.IOException) break
            }
            attempt++
            val delayMs = baseDelayMs * (1 shl (attempt - 1)).coerceAtMost(8)
            kotlinx.coroutines.delay(delayMs)
        }
        throw lastErr ?: IllegalStateException("Unknown error in withRetry")
    }

    override suspend fun transcribeAudio(audioBytes: ByteArray): List<SpeakerUtterance> = withRetry {
        val response: ExternalTranscriptionResponse = client.submitFormWithBinaryData(
            url = transcribeUrl,
            formData = formData {
                append("file", audioBytes, Headers.build {
                        append(HttpHeaders.ContentType, "audio/mp3")
                    append(HttpHeaders.ContentDisposition, "form-data; name=\"file\"; filename=\"audio.mp3\"")
                })
            }
        ).body()
        response.segments.map { SpeakerUtterance(it.speaker, it.text, it.start, it.end) }
    }

    override suspend fun analyzeText(text: String): MeetingSummary = withRetry {
        val resp: ExternalAnalysisTask = client.post(analyzeUrl) {
            contentType(ContentType.Application.Json)
            setBody(mapOf("text" to text))
        }.body()
        MeetingSummary(resp.summary, resp.tasks)
    }
}
