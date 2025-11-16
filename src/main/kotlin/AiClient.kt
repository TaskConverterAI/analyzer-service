package com.taskconvertai

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.client.plugins.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import org.slf4j.LoggerFactory

interface AiClient {
    suspend fun transcribeAudio(audioFilePath: String): List<SpeakerUtterance>
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
    private val log = LoggerFactory.getLogger("ai-client")
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
                if (attempt > 0) log.warn("Retry attempt=$attempt")
                return block().also { log.debug("Успешный ответ после attempt=$attempt") }
            } catch (e: HttpRequestTimeoutException) {
                log.error("Timeout attempt=$attempt: ${e.message}")
                lastErr = e
            } catch (e: Exception) {
                log.error("Ошибка attempt=$attempt: ${e.message}")
                lastErr = e
                if (e !is java.io.IOException) break
            }
            attempt++
            val delayMs = baseDelayMs * (1 shl (attempt - 1)).coerceAtMost(8)
            log.info("Задержка перед повтором ${delayMs}ms")
            kotlinx.coroutines.delay(delayMs)
        }
        throw lastErr ?: IllegalStateException("Unknown error in withRetry")
    }

    override suspend fun transcribeAudio(audioFilePath: String): List<SpeakerUtterance> = withRetry {
        val started = System.currentTimeMillis()
        val file = File(audioFilePath)
        require(file.exists()) { "Audio file not found: $audioFilePath" }
        log.info("Запрос транскрибации file=${file.absolutePath} size=${file.length()} bytes")
        val response: ExternalTranscriptionResponse = client.post(transcribeUrl) {
            contentType(ContentType.Application.Json)
            setBody(mapOf("file_path" to file.absolutePath))
        }.body()
        val duration = System.currentTimeMillis() - started
        log.info("Ответ транскрибации получен segments=${response.segments.size} durationMs=$duration")
        kotlin.runCatching { file.delete() }.onFailure { log.warn("Не удалось удалить файл ${file.absolutePath}: ${it.message}") }
        response.segments.map { SpeakerUtterance(it.speaker, it.text, it.start, it.end) }
    }

    override suspend fun analyzeText(text: String): MeetingSummary = withRetry {
        val started = System.currentTimeMillis()
        log.info("Запрос анализа текста length=${text.length}")
        val resp: ExternalAnalysisTask = client.post(analyzeUrl) {
            contentType(ContentType.Application.Json)
            setBody(mapOf("text" to text))
        }.body()
        val duration = System.currentTimeMillis() - started
        log.info("Ответ анализа получен tasks=${resp.tasks.size} durationMs=$duration")
        MeetingSummary(resp.summary, resp.tasks)
    }
}
