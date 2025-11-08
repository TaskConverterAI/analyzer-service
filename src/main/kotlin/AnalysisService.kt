package com.taskconvertai

import io.ktor.server.application.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlinx.coroutines.runBlocking

private const val DEFAULT_BASE_URL_AI_API: String = "http://localhost:9000"
private const val DEFAULT_TIMEOUT_AI_API: Long = 15000L

object AnalysisProcessor {
    private val scope = CoroutineScope(Dispatchers.Default)
    private lateinit var ai: AiClient

    fun init(app: Application) {
        val cfg = app.environment.config
        val baseUrl = cfg.propertyOrNull("ai.baseUrl")?.getString() ?: DEFAULT_BASE_URL_AI_API
        val timeout = cfg.propertyOrNull("ai.requestTimeoutMs")?.getString()?.toLongOrNull() ?: DEFAULT_TIMEOUT_AI_API
        ai = HttpAiClient(baseUrl, timeout)
        // Ensure tables exist
        runBlocking {
            transaction(AppContext.database) {
                SchemaUtils.createMissingTablesAndColumns(Transcriptions, Analyses)
            }
        }
    }

    fun processAudio(job: AnalysisJob, userId: String, bytes: ByteArray) {
        scope.launch {
            JobRepository.updateStatus(job.jobId, JobStatus.RUNNING)
            try {
                val utterances = ai.transcribeAudio(bytes)
                val merged = UtteranceMerger.merge(utterances)
                persistTranscription(UUID.randomUUID().toString(), job.jobId, userId, merged)
                JobRepository.updateStatus(job.jobId, JobStatus.SUCCEEDED)
            } catch (e: Exception) {
                JobRepository.updateStatus(job.jobId, JobStatus.FAILED, e.message)
            }
        }
    }

    fun processTaskDescription(job: AnalysisJob, userId: String, description: String) {
        scope.launch {
            JobRepository.updateStatus(job.jobId, JobStatus.RUNNING)
            try {
                val summary = ai.analyzeText(description)
                persistAnalysis(job.jobId, userId, summary)
                JobRepository.updateStatus(job.jobId, JobStatus.SUCCEEDED)
            } catch (e: Exception) {
                JobRepository.updateStatus(job.jobId, JobStatus.FAILED, e.message)
            }
        }
    }
}
