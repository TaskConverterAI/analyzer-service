package com.taskconvertai

import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.http.content.*
import io.ktor.utils.io.*
import java.io.ByteArrayOutputStream

fun Application.configureRouting() {
    routing {
        get("/") { call.respondText("OK") }
        post("/audio") {
            val userId = call.request.queryParameters["userID"] ?: return@post call.respond(HttpStatusCode.BadRequest, "userID missing")
            val ct = call.request.contentType()
            if (!ct.match(ContentType.MultiPart.FormData)) {
                return@post call.respond(HttpStatusCode.BadRequest, "multipart/form-data required")
            }
            val multipart = call.receiveMultipart()
            var bytes: ByteArray? = null
            var oversize = false
            multipart.forEachPart { part ->
                if (oversize) { part.dispose(); return@forEachPart }
                when (part) {
                    is PartData.FileItem -> if (part.name == "file" && bytes == null) {
                        val channel: ByteReadChannel = part.provider()
                        val baos = ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (!channel.isClosedForRead) {
                            val read = channel.readAvailable(buf, 0, buf.size)
                            if (read <= 0) break
                            baos.write(buf, 0, read)
                            if (baos.size() > MAX_AUDIO_SIZE_BYTES) {
                                oversize = true
                                break
                            }
                        }
                        bytes = baos.toByteArray()
                    }
                    else -> {}
                }
                part.dispose()
            }
            if (oversize) return@post call.respond(HttpStatusCode.BadRequest, "File too large")
            val fileBytes = bytes ?: return@post call.respond(HttpStatusCode.BadRequest, "file part missing")
            val job = JobRepository.create(userId, JobType.AUDIO)
            AnalysisProcessor.processAudio(job, userId, fileBytes)
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
                    val utt = loadTranscriptionUtterances(jobId) ?: return@get call.respond(HttpStatusCode.NotFound, "Result not found")
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
