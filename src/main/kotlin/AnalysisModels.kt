package com.taskconvertai

import kotlinx.serialization.Serializable

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

