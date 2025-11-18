package com.taskconvertai

import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

object Transcriptions : Table() {
    val id = varchar("id", 128)
    val jobId = varchar("job_id", 128)
    val userId = varchar("user_id", 128)
    val content = text("content") // JSON массив SpeakerUtterance
    override val primaryKey = PrimaryKey(id)
}

object Analyses : Table() {
    val jobId = varchar("job_id", 128)
    val userId = varchar("user_id", 128)
    val summary = text("summary")
    val tasks = text("tasks_json") // JSON массив TaskItem
    override val primaryKey = PrimaryKey(jobId)
}

private val serializationJson = Json { encodeDefaults = true; ignoreUnknownKeys = true }

suspend fun persistTranscription(id: String, jobId: String, userId: String, merged: List<SpeakerUtterance>) = dbQuery {
    Transcriptions.insert {
        it[Transcriptions.id] = id
        it[Transcriptions.jobId] = jobId
        it[Transcriptions.userId] = userId
        it[content] = serializationJson.encodeToString(merged)
    }
}

suspend fun persistAnalysis(jobId: String, userId: String, meetingSummary: MeetingSummary) = dbQuery {
    Analyses.insertIgnore {
        it[Analyses.jobId] = jobId
        it[Analyses.userId] = userId
        it[summary] = meetingSummary.summary
        it[tasks] = serializationJson.encodeToString(meetingSummary.tasks)
    }
}

suspend fun loadAnalysis(jobId: String): Pair<TaskAnalysisResult?, String?> = dbQuery {
    val analysisRow = Analyses.selectAll().where { Analyses.jobId eq jobId }.singleOrNull()
    if (analysisRow == null) return@dbQuery Pair(null, null)

    // Загружаем данные из джобы для получения дополнительных полей
    val jobRow = Jobs.selectAll().where { Jobs.jobId eq jobId }.singleOrNull()

    val summary = analysisRow[Analyses.summary]
    val tasksJson = analysisRow[Analyses.tasks]
    val tasks = runCatching { serializationJson.decodeFromString<List<TaskItem>>(tasksJson) }.getOrDefault(emptyList())

    val geo = if (jobRow != null && jobRow[Jobs.geoLatitude] != null && jobRow[Jobs.geoLongitude] != null) {
        GeoLocation(jobRow[Jobs.geoLatitude]!!, jobRow[Jobs.geoLongitude]!!)
    } else null

    val result = TaskAnalysisResult(
        summary = summary,
        tasks = tasks,
        geo = geo,
        name = jobRow?.get(Jobs.name),
        group = jobRow?.get(Jobs.group),
        data = jobRow?.get(Jobs.data)
    )

    Pair(result, analysisRow[Analyses.userId])
}

suspend fun loadTranscriptionUtterances(jobId: String): List<SpeakerUtterance>? = dbQuery {
    Transcriptions.selectAll().where { Transcriptions.jobId eq jobId }.singleOrNull()?.let { row ->
        val raw = row[Transcriptions.content]
        runCatching { serializationJson.decodeFromString<List<SpeakerUtterance>>(raw) }.getOrNull()
    }
}

suspend fun deleteTranscription(jobId: String) = dbQuery {
    Transcriptions.deleteWhere { Transcriptions.jobId eq jobId }
}

private suspend fun <T> dbQuery(block: suspend () -> T): T = newSuspendedTransaction(Dispatchers.IO) { block() }
