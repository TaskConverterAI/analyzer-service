package com.taskconvertai

import io.ktor.server.application.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.SchemaUtils
import org.jetbrains.exposed.sql.transactions.transaction
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.slf4j.LoggerFactory

private const val DEFAULT_BASE_URL_AI_API: String = "http://localhost:9000"
private const val DEFAULT_TIMEOUT_AI_API: Long = 15000L

object AnalysisProcessor {
    private val scope = CoroutineScope(Dispatchers.Default)
    private lateinit var ai: AiClient
    private val logger = LoggerFactory.getLogger("analysis-processor")

    fun init(app: Application) {
        val cfg = app.environment.config
        val baseUrl = cfg.propertyOrNull("ai.baseUrl")?.getString() ?: DEFAULT_BASE_URL_AI_API
        val timeout = cfg.propertyOrNull("ai.requestTimeoutMs")?.getString()?.toLongOrNull() ?: DEFAULT_TIMEOUT_AI_API
        logger.info("Инициализация AI клиента baseUrl=$baseUrl timeoutMs=$timeout")
        ai = HttpAiClient(baseUrl, timeout)
        runBlocking {
            transaction(AppContext.database) {
                SchemaUtils.createMissingTablesAndColumns(Transcriptions, Analyses)
            }
        }
        logger.info("Таблицы проверены/созданы")
    }

    fun processAudio(job: AnalysisJob, userId: String, audioFilePath: String) {
        scope.launch {
            logger.info("[job=${job.jobId}] Старт обработки аудио user=$userId file=$audioFilePath")
            JobRepository.updateStatus(job.jobId, JobStatus.RUNNING)
            try {
                val utterances = ai.transcribeAudio(audioFilePath).also {
                    logger.info("[job=${job.jobId}] Получено сегментов=${it.size}")
                }
                val merged = UtteranceMerger.merge(utterances).also {
                    logger.info("[job=${job.jobId}] После мерджа сегментов=${it.size}")
                }
                persistTranscription(UUID.randomUUID().toString(), job.jobId, userId, merged)
                logger.info("[job=${job.jobId}] Транскрипт сохранён")
                JobRepository.updateStatus(job.jobId, JobStatus.SUCCEEDED)
                logger.info("[job=${job.jobId}] Завершено успешно")
            } catch (e: Exception) {
                logger.error("[job=${job.jobId}] Ошибка обработки аудио: ${e.message}", e)
                JobRepository.updateStatus(job.jobId, JobStatus.FAILED, e.message)
            }
        }
    }

    fun processTaskDescription(job: AnalysisJob, userId: String, description: String) {
        scope.launch {
            logger.info("[job=${job.jobId}] Старт анализа текста user=$userId length=${description.length}")
            JobRepository.updateStatus(job.jobId, JobStatus.RUNNING)
            try {
                val summary = ai.analyzeText(description)
                logger.info("[job=${job.jobId}] Получено tasks=${summary.tasks.size} summaryLength=${summary.summary.length}")
                persistAnalysis(job.jobId, userId, summary)
                logger.info("[job=${job.jobId}] Анализ сохранён")
                JobRepository.updateStatus(job.jobId, JobStatus.SUCCEEDED)
                logger.info("[job=${job.jobId}] Завершено успешно")
            } catch (e: Exception) {
                logger.error("[job=${job.jobId}] Ошибка анализа текста: ${e.message}", e)
                JobRepository.updateStatus(job.jobId, JobStatus.FAILED, e.message)
            }
        }
    }
}
