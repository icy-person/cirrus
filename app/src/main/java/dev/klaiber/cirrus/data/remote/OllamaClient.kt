package dev.klaiber.cirrus.data.remote

import dev.klaiber.cirrus.data.remote.dto.ChatChunkDto
import dev.klaiber.cirrus.data.remote.dto.ChatRequestDto
import dev.klaiber.cirrus.data.remote.dto.ErrorResponseDto
import dev.klaiber.cirrus.data.remote.dto.MessageDto
import dev.klaiber.cirrus.data.remote.dto.ShowRequestDto
import dev.klaiber.cirrus.data.remote.dto.ShowResponseDto
import dev.klaiber.cirrus.data.remote.dto.TagModelDto
import dev.klaiber.cirrus.data.remote.dto.TagsResponseDto
import dev.klaiber.cirrus.data.remote.dto.ToolCallDto
import dev.klaiber.cirrus.data.remote.dto.ToolCallFunctionDto
import dev.klaiber.cirrus.data.remote.dto.WebFetchRequestDto
import dev.klaiber.cirrus.data.remote.dto.WebFetchResponseDto
import dev.klaiber.cirrus.data.remote.dto.WebSearchRequestDto
import dev.klaiber.cirrus.data.remote.dto.WebSearchResponseDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.Call
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Thin transport over Ollama and OpenAI-compatible local servers.
 *
 * LM Studio exposes an OpenAI-compatible API under `/v1`. When the configured base URL ends in
 * `/v1`, this client switches automatically to `/models` and `/chat/completions`; all higher-level
 * agent, MCP and GitHub code continues to consume the existing Cirrus DTOs.
 */
@Singleton
class OllamaClient @Inject constructor(
    private val httpClient: OkHttpClient,
    private val json: Json,
    private val credentials: ApiCredentials,
) {

    fun streamChat(request: ChatRequestDto): Flow<ChatChunkDto> = flow {
        requireCredentials()
        if (credentials.isOpenAiCompatible()) streamOpenAi(request) else streamOllama(request)
    }.flowOn(Dispatchers.IO)

    private suspend fun FlowCollector<ChatChunkDto>.streamOllama(request: ChatRequestDto) {
        val call = httpClient.newCall(buildRequest("/api/chat", encodeRequest(request.copy(stream = true))))
        currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        val response = try { call.execute() } catch (io: IOException) { throw asOllamaException(io) }
        response.use { httpResponse ->
            if (!httpResponse.isSuccessful) throw errorFor(httpResponse, request.model)
            val source = httpResponse.body.source()
            var sawDone = false
            while (true) {
                val line = try { source.readUtf8Line() } catch (io: IOException) { throw asOllamaException(io) } ?: break
                if (line.isBlank()) continue
                val chunk = try { json.decodeFromString(ChatChunkDto.serializer(), line) }
                catch (e: Exception) { throw OllamaException.Malformed("Unreadable stream chunk: $line", e) }
                chunk.error?.let { throw OllamaException.ServerError(200, it) }
                emit(chunk)
                if (chunk.done) { sawDone = true; break }
            }
            if (!sawDone) throw OllamaException.Truncated()
        }
    }

    private suspend fun FlowCollector<ChatChunkDto>.streamOpenAi(request: ChatRequestDto) {
        val call = httpClient.newCall(buildRequest("/chat/completions", encodeOpenAiRequest(request, true)))
        currentCoroutineContext().job.invokeOnCompletion { call.cancel() }
        val response = try { call.execute() } catch (io: IOException) { throw asOllamaException(io) }
        response.use { httpResponse ->
            if (!httpResponse.isSuccessful) throw errorFor(httpResponse, request.model)
            val source = httpResponse.body.source()
            val toolCalls = linkedMapOf<Int, MutableToolCall>()
            var sawDone = false
            while (true) {
                val line = try { source.readUtf8Line() } catch (io: IOException) { throw asOllamaException(io) } ?: break
                if (line.isBlank() || line.startsWith(":")) continue
                if (!line.startsWith("data:")) continue
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") {
                    if (toolCalls.isNotEmpty()) {
                        emit(ChatChunkDto(model = request.model, message = MessageDto(
                            role = "assistant",
                            toolCalls = toolCalls.toSortedMap().values.map { it.toDto() },
                        )))
                    }
                    emit(ChatChunkDto(model = request.model, done = true))
                    sawDone = true
                    break
                }
                val root = try { json.parseToJsonElement(data).jsonObject }
                catch (e: Exception) { throw OllamaException.Malformed("Unreadable OpenAI stream chunk: $data", e) }
                root["error"]?.let { error ->
                    val message = runCatching { error.jsonObject["message"]?.jsonPrimitive?.content }.getOrNull()
                    throw OllamaException.ServerError(200, message)
                }
                val delta = root["choices"]?.jsonArrayOrEmpty()?.firstOrNull()?.jsonObject?.get("delta")?.jsonObject
                    ?: continue
                val content = delta["content"]?.jsonPrimitive?.contentOrNull.orEmpty()
                val thinking = delta["reasoning_content"]?.jsonPrimitive?.contentOrNull
                    ?: delta["reasoning"]?.jsonPrimitive?.contentOrNull
                if (content.isNotEmpty() || thinking != null) {
                    emit(ChatChunkDto(
                        model = root["model"]?.jsonPrimitive?.contentOrNull ?: request.model,
                        message = MessageDto(role = "assistant", content = content, thinking = thinking),
                    ))
                }
                delta["tool_calls"]?.jsonArrayOrEmpty()?.forEach { element ->
                    val tc = element.jsonObject
                    val index = tc["index"]?.jsonPrimitive?.intOrNull ?: 0
                    val state = toolCalls.getOrPut(index) { MutableToolCall() }
                    tc["id"]?.jsonPrimitive?.contentOrNull?.let { state.id = it }
                    tc["type"]?.jsonPrimitive?.contentOrNull?.let { state.type = it }
                    tc["function"]?.jsonObject?.let { fn ->
                        fn["name"]?.jsonPrimitive?.contentOrNull?.let { state.name = it }
                        fn["arguments"]?.jsonPrimitive?.contentOrNull?.let { state.arguments.append(it) }
                    }
                }
            }
            if (!sawDone) throw OllamaException.Truncated()
        }
    }

    suspend fun listModels(): List<TagModelDto> = withContext(Dispatchers.IO) {
        if (credentials.isOpenAiCompatible()) {
            val response = executeText(httpClient.newCall(buildRequest("/models", null)))
            val data = json.parseToJsonElement(response).jsonObject["data"]?.jsonArrayOrEmpty() ?: JsonArray(emptyList())
            data.mapNotNull { element ->
                val id = element.jsonObject["id"]?.jsonPrimitive?.contentOrNull
                id?.let { TagModelDto(name = it) }
            }
        } else {
            executeForJson(httpClient.newCall(buildRequest("/api/tags", null)), TagsResponseDto.serializer()).models
        }
    }

    suspend fun showModel(model: String): ShowResponseDto = withContext(Dispatchers.IO) {
        if (credentials.isOpenAiCompatible()) {
            // There is no `/api/show`-equivalent on an OpenAI-compatible host such as LM Studio,
            // so nothing here is actually probed. `completion` and `tools` are reported anyway,
            // rather than an empty set, because every model an OpenAI-style `/chat/completions`
            // endpoint serves supports both — Cirrus already sends `tools` on every such request
            // regardless of this badge. Context length, family and quantisation stay null: unlike
            // capabilities, those vary per model and guessing them would be actively misleading.
            ShowResponseDto(
                capabilities = listOf("completion", "tools"),
                remoteModel = model,
                remoteHost = credentials.baseUrl,
            )
        } else {
            val payload = json.encodeToString(ShowRequestDto.serializer(), ShowRequestDto(model))
            executeForJson(httpClient.newCall(buildRequest("/api/show", payload)), ShowResponseDto.serializer())
        }
    }

    suspend fun webSearch(query: String, maxResults: Int): WebSearchResponseDto = withContext(Dispatchers.IO) {
        requireCredentials()
        val payload = json.encodeToString(WebSearchRequestDto.serializer(), WebSearchRequestDto(query, maxResults))
        executeForJson(httpClient.newCall(buildRequest("/api/web_search", payload)), WebSearchResponseDto.serializer())
    }

    suspend fun webFetch(url: String): WebFetchResponseDto = withContext(Dispatchers.IO) {
        requireCredentials()
        val payload = json.encodeToString(WebFetchRequestDto.serializer(), WebFetchRequestDto(url))
        executeForJson(httpClient.newCall(buildRequest("/api/web_fetch", payload)), WebFetchResponseDto.serializer())
    }

    suspend fun validateCredentials(model: String): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            requireCredentials()
            if (credentials.isOpenAiCompatible()) {
                val payload = encodeOpenAiRequest(
                    ChatRequestDto(model = model, messages = listOf(MessageDto("user", "ping"))),
                    false,
                )
                val response = httpClient.newCall(buildRequest("/chat/completions", payload)).execute()
                response.use { if (!it.isSuccessful) throw errorFor(it, model) }
            } else {
                val probe = ChatRequestDto(
                    model = model,
                    messages = listOf(MessageDto("user", "ping")),
                    stream = false,
                    options = JsonObject(mapOf("num_predict" to JsonPrimitive(1))),
                )
                val response = httpClient.newCall(buildRequest("/api/chat", encodeRequest(probe))).execute()
                response.use { if (!it.isSuccessful) throw errorFor(it, model) }
            }
        }
    }

    fun encodeRequest(request: ChatRequestDto): String =
        if (credentials.isOpenAiCompatible()) encodeOpenAiRequest(request, request.stream)
        else json.encodeToString(ChatRequestDto.serializer(), request)

    private fun encodeOpenAiRequest(request: ChatRequestDto, stream: Boolean): String = buildJsonObject {
        put("model", request.model)
        putJsonArray("messages") { request.messages.forEach { add(toOpenAiMessage(it)) } }
        put("stream", stream)
        request.tools?.let { put("tools", JsonArray(it)) }
        request.format?.let { put("response_format", it) }
        request.think?.let { put("reasoning", it) }
        request.options?.forEach { (key, value) ->
            put(if (key == "num_predict") "max_tokens" else key, value)
        }
    }.toString()

    private fun toOpenAiMessage(message: MessageDto): JsonObject = buildJsonObject {
        put("role", message.role)
        message.toolCallId?.let { put("tool_call_id", it) }
        message.toolName?.let { put("name", it) }
        message.toolCalls?.let { calls ->
            put("tool_calls", JsonArray(calls.map { call ->
                buildJsonObject {
                    put("id", call.id ?: "call_${call.function.name}")
                    put("type", call.type ?: "function")
                    putJsonObject("function") {
                        put("name", call.function.name)
                        put("arguments", call.function.arguments.toString())
                    }
                }
            }))
        }
        if (message.images.isNullOrEmpty()) {
            put("content", message.content)
        } else {
            putJsonArray("content") {
                if (message.content.isNotEmpty()) add(buildJsonObject {
                    put("type", "text")
                    put("text", message.content)
                })
                message.images.forEach { image -> add(buildJsonObject {
                    put("type", "image_url")
                    putJsonObject("image_url") { put("url", "data:image/jpeg;base64,$image") }
                }) }
            }
        }
    }

    private fun requireCredentials() {
        if (credentials.apiKey == null && credentials.isCloudHost()) throw OllamaException.MissingApiKey()
    }

    private fun buildRequest(path: String, body: String?): Request {
        val builder = Request.Builder().url(credentials.baseUrl + path).header("Accept", "application/json")
        if (body != null) builder.post(body.toRequestBody(JSON_MEDIA_TYPE))
        return builder.build()
    }

    private fun executeText(call: Call): String {
        val response = try { call.execute() } catch (io: IOException) { throw asOllamaException(io) }
        response.use { if (!it.isSuccessful) throw errorFor(it, null); return it.body.string() }
    }

    private fun <T> executeForJson(call: Call, deserializer: kotlinx.serialization.DeserializationStrategy<T>): T =
        json.decodeFromString(deserializer, executeText(call))

    private fun errorFor(response: Response, model: String?): OllamaException {
        val raw = runCatching { response.body.string() }.getOrNull()
        val detail = raw?.let { text ->
            runCatching { json.decodeFromString(ErrorResponseDto.serializer(), text).error }.getOrNull()
                ?: runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content }.getOrNull()
                ?: text.takeIf { it.isNotBlank() && it.length < MAX_INLINE_ERROR_LENGTH }
        }
        return when (response.code) {
            401, 403 -> OllamaException.Unauthorized(detail)
            404 -> OllamaException.ModelNotFound(model ?: "unknown", detail)
            429 -> OllamaException.RateLimited(detail, response.header("Retry-After")?.toLongOrNull())
            else -> OllamaException.ServerError(response.code, detail)
        }
    }

    private fun asOllamaException(io: IOException): OllamaException = if (io is OllamaException) io else OllamaException.Network(io)

    private data class MutableToolCall(
        var id: String? = null,
        var type: String? = null,
        var name: String = "",
        val arguments: StringBuilder = StringBuilder(),
    ) {
        fun toDto() = ToolCallDto(
            id = id,
            type = type,
            function = ToolCallFunctionDto(
                name = name,
                arguments = runCatching { Json.parseToJsonElement(arguments.toString()).jsonObject }
                    .getOrDefault(JsonObject(emptyMap())),
            ),
        )
    }

    private fun JsonElement.jsonArrayOrEmpty(): JsonArray = this as? JsonArray ?: JsonArray(emptyList())
    private val JsonPrimitive.contentOrNull: String? get() = content.takeIf { it.isNotEmpty() }
    private val JsonPrimitive.intOrNull: Int? get() = content.toIntOrNull()

    private companion object {
        val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        const val MAX_INLINE_ERROR_LENGTH = 500
    }
}
