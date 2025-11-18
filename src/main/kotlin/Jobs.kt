package com.taskconvertai

import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.serialization.Serializable
import org.jetbrains.exposed.sql.*
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
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
    val errorMessage: String? = null,
    val geoLatitude: Double? = null,
    val geoLongitude: Double? = null,
    val name: String? = null,
    val group: String? = null,
    val data: String? = null
)

object Jobs : Table("jobs") {
    val jobId = varchar("job_id", 160)
    val userId = varchar("user_id", 120)
    val type = enumerationByName("type", 16, JobType::class)
    val status = enumerationByName("status", 16, JobStatus::class)
    val createdAt = varchar("created_at", 50)
    val updatedAt = varchar("updated_at", 50)
    val errorMessage = text("error_message").nullable()
    val geoLatitude = double("geo_latitude").nullable()
    val geoLongitude = double("geo_longitude").nullable()
    val name = varchar("name", 255).nullable()
    val group = varchar("group_name", 255).nullable()
    val data = text("data").nullable()
    override val primaryKey = PrimaryKey(jobId)
}

private suspend fun <T> dbJob(block: suspend () -> T): T = newSuspendedTransaction(Dispatchers.IO) { block() }

object JobRepository {
    suspend fun create(
        userId: String,
        type: JobType,
        geoLatitude: Double? = null,
        geoLongitude: Double? = null,
        name: String? = null,
        group: String? = null,
        data: String? = null
    ): AnalysisJob = dbJob {
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
            it[Jobs.geoLatitude] = geoLatitude
            it[Jobs.geoLongitude] = geoLongitude
            it[Jobs.name] = name
            it[Jobs.group] = group
            it[Jobs.data] = data
        }
        AnalysisJob(id, userId, type, JobStatus.PENDING, now, now, null, geoLatitude, geoLongitude, name, group, data)
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
                errorMessage = row[Jobs.errorMessage],
                geoLatitude = row[Jobs.geoLatitude],
                geoLongitude = row[Jobs.geoLongitude],
                name = row[Jobs.name],
                group = row[Jobs.group],
                data = row[Jobs.data]
            )
        }
    }

    /**
     * Возвращает список задач (jobs).
     * Failed задачи удаляются после того, как были возвращены пользователю.
     * @param userIdFilter если указан, возвращаются только задачи конкретного пользователя.
     * @return Список [AnalysisJob] отсортированный по времени создания (по убыванию).
     */
    @OptIn(DelicateCoroutinesApi::class)
    suspend fun list(userIdFilter: String? = null): List<AnalysisJob> = dbJob {
        val base = Jobs.selectAll()
        val filtered = if (userIdFilter != null) base.where { Jobs.userId eq userIdFilter } else base
        val jobs = filtered.orderBy(Jobs.createdAt, SortOrder.DESC).map { row ->
            AnalysisJob(
                jobId = row[Jobs.jobId],
                submitterUserId = row[Jobs.userId],
                type = row[Jobs.type],
                status = row[Jobs.status],
                createdAt = row[Jobs.createdAt],
                updatedAt = row[Jobs.updatedAt],
                errorMessage = row[Jobs.errorMessage],
                geoLatitude = row[Jobs.geoLatitude],
                geoLongitude = row[Jobs.geoLongitude],
                name = row[Jobs.name],
                group = row[Jobs.group],
                data = row[Jobs.data]
            )
        }

        // Находим failed задачи для последующего удаления
        val failedJobIds = jobs.filter { it.status == JobStatus.FAILED }.map { it.jobId }

        // Возвращаем список (failed задачи будут удалены асинхронно после возврата)
        if (failedJobIds.isNotEmpty()) {
            // Запускаем удаление failed задач в отдельной корутине
            GlobalScope.launch {
                try {
                    dbJob {
                        Jobs.deleteWhere { Jobs.jobId inList failedJobIds }
                    }
                } catch (e: Exception) {
                    // Логируем ошибку, но не прерываем основной поток
                    println("Ошибка при удалении failed задач: ${e.message}")
                }
            }
        }

        jobs
    }

    /**
     * Удаляет задачи старше указанного количества часов.
     * @param olderThanHours количество часов (по умолчанию 24 часа)
     * @return количество удаленных записей
     */
    suspend fun deleteOldJobs(olderThanHours: Long = 24): Int = dbJob {
        val cutoffTime = Instant.now().minusSeconds(olderThanHours * 3600)
        val cutoffTimeString = cutoffTime.toString()

        Jobs.deleteWhere { Jobs.createdAt lessEq cutoffTimeString }
    }


}
