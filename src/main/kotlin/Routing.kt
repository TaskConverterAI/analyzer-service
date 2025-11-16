package com.taskconvertai

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*

import io.ktor.utils.io.*
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import org.slf4j.LoggerFactory

const val FILE_PROGRESS_LOG_INTERVAL_BYTES = 5 * 1024 * 1024 // 5 MB

fun Application.configureRouting() {
    routing {
        get("/") { call.respondText("OK") }
        post("/audio") {
            val logger = LoggerFactory.getLogger("audio-upload")
            val userId = call.request.queryParameters["userID"] ?: return@post call.respond(
                HttpStatusCode.BadRequest,
                "userID missing"
            )
            val ct = call.request.contentType()
            if (!ct.match(ContentType.MultiPart.FormData)) {
                return@post call.respond(HttpStatusCode.BadRequest, "multipart/form-data required")
            }
            // Сначала читаем начало multipart для извлечения filename
            logger.info("[upload] Начинаем чтение тела запроса для извлечения имени файла")
            val channel = call.receiveChannel()

            // Читаем первые 2KB для поиска filename в заголовках multipart
            val headerBuf = ByteArray(2048)
            var headerBytesRead = 0
            val tempChannel = ByteArray(8192) // буфер для чтения

            logger.debug("[upload] Читаем заголовки multipart для извлечения filename")
            while (headerBytesRead < headerBuf.size) {
                val read = channel.readAvailable(tempChannel, 0, minOf(tempChannel.size, headerBuf.size - headerBytesRead))
                if (read <= 0) break
                System.arraycopy(tempChannel, 0, headerBuf, headerBytesRead, read)
                headerBytesRead += read
                // Ищем двойной перевод строки (конец заголовков)
                val headerStr = String(headerBuf, 0, headerBytesRead, Charsets.UTF_8)
                if (headerStr.contains("\r\n\r\n") || headerStr.contains("\n\n")) {
                    break
                }
            }

            // Извлекаем filename из заголовков
            val headerStr = String(headerBuf, 0, headerBytesRead, Charsets.UTF_8)
            logger.debug("[upload] Заголовки multipart: ${headerStr.take(500)}")

            val originalFileName = run {
                val filenameRegex = """filename\s*=\s*"([^"]+)"""".toRegex(RegexOption.IGNORE_CASE)
                val match = filenameRegex.find(headerStr)
                val name = match?.groupValues?.get(1)
                logger.info("[upload] Извлечено имя файла из multipart: $name")
                name
            }

            // Определяем безопасное расширение
            val safeExt = run {
                val name = originalFileName ?: "audio"
                val extCandidate = name.substringAfterLast('.', "").lowercase()
                val allowed = extCandidate.isNotEmpty() && extCandidate.length <= 10 && extCandidate.all { it.isLetterOrDigit() }
                if (allowed) {
                    logger.info("[upload] Используем оригинальное расширение: $extCandidate")
                    extCandidate
                } else {
                    val fallback = when {
                        name.contains(".mp3", true) -> "mp3"
                        name.contains(".wav", true) -> "wav"
                        name.contains(".m4a", true) -> "m4a"
                        name.contains(".ogg", true) -> "ogg"
                        else -> "bin"
                    }
                    logger.info("[upload] Используем fallback расширение: $fallback")
                    fallback
                }
            }

            val tempDir = File(System.getProperty("java.io.tmpdir"))
            val fileName = "audio_${UUID.randomUUID()}.$safeExt"
            val audioFile = File(tempDir, fileName)
            logger.info("[upload] Создан временный файл ${audioFile.absolutePath}")

            var oversize = false
            var totalBytes = 0L
            val buf = ByteArray(64 * 1024)
            var stallCount = 0
            var lastDataTs = System.currentTimeMillis()
            val startReadTs = lastDataTs
            var nextLog = FILE_PROGRESS_LOG_INTERVAL_BYTES.toLong()

            logger.debug("[upload] Начинаем чтение channel bufferSize=${buf.size}")

            try {
                FileOutputStream(audioFile).use { fos ->
                    // Сначала записываем уже прочитанные заголовки
                    fos.write(headerBuf, 0, headerBytesRead)
                    totalBytes += headerBytesRead
                    logger.debug("[upload] Записаны заголовки multipart: $headerBytesRead bytes")

                    // Продолжаем чтение остальных данных
                    while (true) {
                        val read = channel.readAvailable(buf, 0, buf.size)
                        logger.trace("[upload] readAvailable returned=$read isClosedForRead=${channel.isClosedForRead} totalBytes=$totalBytes")
                        if (read == -1) {
                            logger.debug("[upload] Канал завершён (read=-1)")
                            break
                        }
                        if (read > 0) {
                            fos.write(buf, 0, read)
                            totalBytes += read
                            stallCount = 0
                            lastDataTs = System.currentTimeMillis()
                            if (totalBytes > MAX_AUDIO_SIZE_BYTES) {
                                oversize = true
                                logger.warn("[upload] Превышение лимита totalBytes=$totalBytes, прерываем")
                                break
                            }
                            if (totalBytes >= nextLog) {
                                logger.info("[upload] Прогресс: ${(totalBytes / 1024 / 1024)} MB записано")
                                nextLog += FILE_PROGRESS_LOG_INTERVAL_BYTES
                            }
                        } else { // read == 0
                            stallCount++
                            val now = System.currentTimeMillis()
                            if (stallCount % 100 == 0) {
                                logger.debug("[upload] Временно нет данных stallCount=$stallCount totalMB=${totalBytes / 1024 / 1024}")
                            }
                            if (now - lastDataTs > 5000) {
                                logger.warn("[upload] Долгое ожидание данных ${now - lastDataTs}ms")
                            }
                            kotlinx.coroutines.delay(5)
                        }
                    }
                }
            } catch (e: Exception) {
                logger.error("[upload] Ошибка записи в файл: ${e.message}", e)
                oversize = true
                runCatching { audioFile.delete() }
            }

            val readDuration = System.currentTimeMillis() - startReadTs
            logger.info("[upload] Чтение завершено totalBytes=$totalBytes (${totalBytes / 1024 / 1024} MB) durationMs=$readDuration oversize=$oversize")

            if (oversize) {
                runCatching { audioFile.delete() }
                return@post call.respond(HttpStatusCode.BadRequest, "File too large")
            }
            if (totalBytes == 0L) {
                runCatching { audioFile.delete() }
                return@post call.respond(HttpStatusCode.BadRequest, "empty request")
            }

            val finalAudioFile = audioFile
            logger.info("[upload] Используем файл ${finalAudioFile.absolutePath} размер=${finalAudioFile.length()} bytes")
            val job = JobRepository.create(userId, JobType.AUDIO)
            logger.info("[upload] Создана job=${job.jobId} для user=$userId путь=${finalAudioFile.absolutePath}")
            AnalysisProcessor.processAudio(job, userId, finalAudioFile.absolutePath)
            call.respond(mapOf("jobId" to job.jobId))
        }

        post("/task") {
            val userId = call.request.queryParameters["userID"] ?: return@post call.respond(HttpStatusCode.BadRequest,"userID missing")
            val description = call.receiveText()
            val job = JobRepository.create(userId, JobType.TASK)
            AnalysisProcessor.processTaskDescription(job, userId, description)
            call.respond(mapOf("jobId" to job.jobId))
        }
        get("/jobs/{jobId}") {
            val jobId = call.parameters["jobId"] ?: return@get call.respond(HttpStatusCode.BadRequest, "jobId missing")
            val job = JobRepository.get(jobId) ?: return@get call.respond(HttpStatusCode.NotFound, "Not found")
            call.respond(job)
        }
        get("/jobs/{jobId}/result") {
            val jobId = call.parameters["jobId"] ?: return@get call.respond(HttpStatusCode.BadRequest, "jobId missing")
            val job = JobRepository.get(jobId) ?: return@get call.respond(HttpStatusCode.NotFound, "Not found")
            if (job.status != JobStatus.SUCCEEDED) return@get call.respond(HttpStatusCode.Conflict, "Job not finished")
            when (job.type) {
                JobType.AUDIO -> {
                    val utt = loadTranscriptionUtterances(jobId) ?: return@get call.respond(
                        HttpStatusCode.NotFound,
                        "Result not found"
                    )
                    deleteTranscription(jobId)
                    val public = utt.map { PublicSpeakerUtterance(it.speaker, it.text) }
                    call.respond(public)
                }

                JobType.TASK -> {
                    val (analysis, _) = loadAnalysis(jobId)
                    if (analysis == null) return@get call.respond(HttpStatusCode.NotFound, "Result not found")
                    call.respond(analysis)
                }
            }
        }
    }
}
