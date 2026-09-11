package dev.klaiber.cirrus.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ToolInvocation(
    val id: String,
    val name: String,
    /** Raw JSON object of arguments, kept verbatim so the inspector can show exactly what ran. */
    val argumentsJson: String,
    val resultJson: String? = null,
    val errorMessage: String? = null,
    val durationMs: Long? = null,
    /** Backend-provided tool-call id, required by OpenAI-compatible follow-up messages. */
    val toolCallId: String? = null,
) {
    val isComplete: Boolean get() = resultJson != null || errorMessage != null
}

@Serializable
data class GenerationStats(
    val totalDurationNs: Long? = null,
    val loadDurationNs: Long? = null,
    val promptEvalCount: Int? = null,
    val promptEvalDurationNs: Long? = null,
    val evalCount: Int? = null,
    val evalDurationNs: Long? = null,
    val doneReason: String? = null,
    val timeToFirstTokenMs: Long? = null,
)

@Serializable
data class Attachment(
    val localPath: String,
    val displayName: String,
    val kind: Kind,
    val extractedText: String? = null,
) {
    @Serializable
enum class Kind { IMAGE, DOCUMENT }
}

@Serializable
data class ChatMessage(
    val id: String,
    val conversationId: String,
    val role: Role,
    val content: String,
    val thinking: String? = null,
    val createdAt: Long,
    val sequence: Int,
    val model: String? = null,
    val stats: GenerationStats? = null,
    val toolInvocations: List<ToolInvocation> = emptyList(),
    val errorMessage: String? = null,
    val isStreaming: Boolean = false,
    val attachments: List<Attachment> = emptyList(),
    val rawRequestJson: String? = null,
)

@Serializable
enum class Role(val wire: String) {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant"),
    TOOL("tool"),
}
