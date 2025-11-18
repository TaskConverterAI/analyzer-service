package com.taskconvertai

import kotlinx.serialization.Serializable

@Serializable
data class GeoLocation(
    val latitude: Double,
    val longitude: Double
)

@Serializable
data class TaskRequest(
    val description: String,
    val geo: GeoLocation? = null,
    val name: String? = null,
    val group: String? = null,
    val data: String? = null
)

@Serializable
data class TaskItem(
    val title: String,
    val description: String,
    val assignee: String? = null
)

@Serializable
data class MeetingSummary(
    val summary: String,
    val tasks: List<TaskItem>
)

@Serializable
data class TaskAnalysisResult(
    val summary: String,
    val tasks: List<TaskItem>,
    val geo: GeoLocation? = null,
    val name: String? = null,
    val group: String? = null,
    val data: String? = null
)

