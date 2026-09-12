package dev.klaiber.cirrus.data.remote

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshot of the connection settings that OkHttp needs synchronously.
 *
 * The settings themselves live in DataStore behind suspending reads, but an [okhttp3.Interceptor]
 * runs on a blocking thread and cannot suspend. A coroutine in [dev.klaiber.cirrus.data.repository.SettingsRepository]
 * mirrors the persisted values into these volatile fields, so the interceptor reads without blocking.
 */
@Singleton
class ApiCredentials @Inject constructor() {

    @Volatile
    var apiKey: String? = null
        private set

    @Volatile
    var baseUrl: String = DEFAULT_BASE_URL
        private set

    fun update(apiKey: String?, baseUrl: String) {
        this.apiKey = apiKey?.takeIf { it.isNotBlank() }
        this.baseUrl = normalizeBaseUrl(baseUrl)
    }

    /** True when requests can be authenticated. Local hosts do not need a key. */
    fun isConfigured(): Boolean = apiKey != null || !isCloudHost()

    fun isCloudHost(): Boolean = baseUrl.contains("ollama.com", ignoreCase = true)

    /** True for OpenAI-compatible endpoints such as LM Studio (`http://host:1234/v1`). */
    fun isOpenAiCompatible(): Boolean = baseUrl.trimEnd('/').endsWith("/v1", ignoreCase = true)

    companion object {
        const val DEFAULT_BASE_URL = "https://ollama.com"

        /** Trims trailing slashes and a trailing `/api` so callers can append `/api/...` safely. */
        fun normalizeBaseUrl(raw: String): String {
            val trimmed = raw.trim().ifEmpty { DEFAULT_BASE_URL }
            val withScheme = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
                trimmed
            } else {
                "https://$trimmed"
            }
            return withScheme.trimEnd('/').removeSuffix("/api").trimEnd('/')
        }
        /**
         * Converts a user-facing LM Studio address into the actual API URL.
         *
         * Accepted:
         * 127.0.0.1:1234
         * 192.168.1.10:1234
         * http://192.168.1.10:1234
         * http://192.168.1.10:1234/v1
         */

        fun normalizeLMStudioUrl(raw: String): String{
            var value = raw.trim();

            if (value.isEmpty()) {
                return ""
            }

            value = value
                .removePrefix("http://")
                .removePrefix("https://")
                .trimEnd("/)

            if (value.endsWith("v1" , ignoreCase = true)){
                value = value.dropLast(3).trimEnd("/")
            }

            return "http://$value/v1"

        }

        /**
         * Converts a stored LM Studio URL back into the value shown in the UI.
         *
         * http://192.168.1.10:1234/v1
         * -> 192.168.1.10:1234
         */

         fun LMStudioAddress(raw: String): String {
            var value = raw.trim();

            if (value.isEmpty()){
                return ""
            }

            value = value
                .removePrefix("http://")
                .removePrefix("https://")
                .trimEnd('/')

            if (value.endsWith("/v1", ignoreCase = true)) {
                value = value.dropLast(3).trimEnd('/')
            }

            return value


         }

    }
}
