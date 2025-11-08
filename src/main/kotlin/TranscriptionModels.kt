package com.taskconvertai

import kotlinx.serialization.Serializable

@Serializable
data class SpeakerUtterance(
    val speaker: String?,
    val text: String,
    val start: Double? = null,
    val end: Double? = null
)

@Serializable
data class PublicSpeakerUtterance(
    val speaker: String?,
    val text: String
)

object UtteranceMerger {
    fun merge(input: List<SpeakerUtterance>): List<SpeakerUtterance> {
        if (input.isEmpty()) return emptyList()
        val out = mutableListOf<SpeakerUtterance>()
        var buffer = input.first()
        for (i in 1 until input.size) {
            val curr = input[i]
            if (curr.speaker == buffer.speaker) {
                buffer = buffer.copy(
                    text = buffer.text + " " + curr.text.trim(),
                    end = curr.end ?: buffer.end
                )
            } else {
                out += buffer
                buffer = curr
            }
        }
        out += buffer
        return out
    }
}
