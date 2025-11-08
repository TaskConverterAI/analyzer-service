package com.taskconvertai

import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import kotlinx.coroutines.Dispatchers
import java.time.Instant
import java.util.UUID

@Serializable
enum class JobStatus { PENDING, RUNNING, SUCCEEDED, FAILED }

@Serializable
enum class JobType { AUDIO, TASK }

@Serializable
data class AnalysisJob(
    val jobId: String,
    val submitterUserId: String,
    val type: JobType,
    val status: JobStatus,
    val createdAt: String,
    val updatedAt: String,
    val errorMessage: String? = null
)

object Jobs : Table("jobs") {
    val jobId = varchar("job_id", 160)
    val userId = varchar("user_id", 120)
    val type = enumerationByName("type", 16, JobType::class)
    val status = enumerationByName("status", 16, JobStatus::class)
    val createdAt = varchar("created_at", 50)
    val updatedAt = varchar("updated_at", 50)
    val errorMessage = text("error_message").nullable()
    override val primaryKey = PrimaryKey(jobId)
}

private suspend fun <T> dbJob(block: suspend () -> T): T = newSuspendedTransaction(Dispatchers.IO) { block() }

object JobRepository {
    suspend fun create(userId: String, type: JobType): AnalysisJob = dbJob {
        val id = "job_${UUID.randomUUID()}_${type.name.lowercase()}"
        val now = Instant.now().toString()
        Jobs.insert {
            val userIdParam = userId
            it[jobId] = id
            it[Jobs.userId] = userIdParam
            it[Jobs.type] = type
            it[status] = JobStatus.PENDING
            it[createdAt] = now
            it[updatedAt] = now
            it[errorMessage] = null
        }
        AnalysisJob(id, userId, type, JobStatus.PENDING, now, now, null)
    }

    suspend fun updateStatus(jobIdValue: String, statusValue: JobStatus, error: String? = null) = dbJob {
        val now = Instant.now().toString()
        Jobs.update({ Jobs.jobId eq jobIdValue }) {
            it[status] = statusValue
            it[updatedAt] = now
            it[errorMessage] = error
        }
    }

    suspend fun get(jobIdValue: String): AnalysisJob? = dbJob {
        Jobs.selectAll().where { Jobs.jobId eq jobIdValue }.singleOrNull()?.let { row ->
            AnalysisJob(
                jobId = row[Jobs.jobId],
                submitterUserId = row[Jobs.userId],
                type = row[Jobs.type],
                status = row[Jobs.status],
                createdAt = row[Jobs.createdAt],
                updatedAt = row[Jobs.updatedAt],
                errorMessage = row[Jobs.errorMessage]
            )
        }
    }
}
