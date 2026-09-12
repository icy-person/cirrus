package dev.klaiber.cirrus.data.remote

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Snapshot of the connection settings that OkHttp needs synchronously.
 *
 * The settings themselves live in DataStore behind suspending reads, but an
 * [okhttp3.Interceptor] runs on a blocking thread and cannot suspend.
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

    /**
     * True when requests can be authenticated or the configured endpoint is local.
     */
    fun isConfigured(): Boolean =
        apiKey != null || !isCloudHost()

    /**
     * True for Ollama's hosted API.
     */
    fun isCloudHost(): Boolean =
        baseUrl.contains("ollama.com", ignoreCase = true)

    /**
     * True for OpenAI-compatible endpoints such as LM Studio.
     */
    fun isOpenAiCompatible(): Boolean =
        baseUrl.trimEnd('/')
            .endsWith("/v1", ignoreCase = true)

    companion object {

        const val DEFAULT_BASE_URL = "https://ollama.com"

        /**
         * Normalizes a general backend URL.
         *
         * Examples:
         *
         * ollama.com
         * -> https://ollama.com
         *
         * https://ollama.com/
         * -> https://ollama.com
         *
         * http://192.168.1.10:11434/
         * -> http://192.168.1.10:11434
         */
        fun normalizeBaseUrl(raw: String): String {
            val trimmed = raw.trim().ifEmpty {
                DEFAULT_BASE_URL
            }

            val withScheme =
                if (
                    trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true)
                ) {
                    trimmed
                } else {
                    "https://$trimmed"
                }

            return withScheme
                .trimEnd('/')
                .removeSuffix("/api")
                .trimEnd('/')
        }

        /**
         * Converts the user-facing LM Studio address to the actual
         * OpenAI-compatible API base URL.
         *
         * User enters:
         *
         * 127.0.0.1:1234
         * 192.168.1.10:1234
         *
         * Cirrus stores:
         *
         * http://127.0.0.1:1234/v1
         * http://192.168.1.10:1234/v1
         *
         * The function also accepts a full URL so previously saved
         * configurations remain compatible.
         */
        fun normalizeLmStudioAddress(raw: String): String {
            var value = raw.trim()

            if (value.isEmpty()) {
                return ""
            }

            value = value
                .removePrefix("http://")
                .removePrefix("https://")
                .trimEnd('/')

            if (value.endsWith("/v1", ignoreCase = true)) {
                value = value
                    .dropLast(3)
                    .trimEnd('/')
            }

            return "http://$value/v1"
        }

        /**
         * Converts the internally stored LM Studio URL back into the
         * user-facing IP:PORT representation.
         *
         * Example:
         *
         * http://192.168.1.10:1234/v1
         * -> 192.168.1.10:1234
         */
        fun lmStudioAddressFromUrl(raw: String): String {
            var value = raw.trim()

            if (value.isEmpty()) {
                return ""
            }

            value = value
                .removePrefix("http://")
                .removePrefix("https://")
                .trimEnd('/')

            if (value.endsWith("/v1", ignoreCase = true)) {
                value = value
                    .dropLast(3)
                    .trimEnd('/')
            }

            return value
        }
    }
}
```
m/
         * -> https://ollama.com
         *
         * http://192.168.1.10:11434/
         * -> http://192.168.1.10:11434
         */
        fun normalizeBaseUrl(raw: String): String {
            val trimmed = raw.trim().ifEmpty {
                DEFAULT_BASE_URL
            }

            val withScheme =
                if (
                    trimmed.startsWith("http://", ignoreCase = true) ||
                    trimmed.startsWith("https://", ignoreCase = true)
                ) {
                    trimmed
                } else {
                    "https://$trimmed"
                }

            return withScheme
                .trimEnd('/')
                .removeSuffix("/api")
                .trimEnd('/')
        }

        /**
         * Converts the user-facing LM Studio address to the actual
         * OpenAI-compatible API base URL.
         *
         * User enters:
         *
         * 127.0.0.1:1234
         * 192.168.1.10:1234
         *
         * Cirrus stores:
         *
         * http://127.0.0.1:1234/v1
         * http://192.168.1.10:1234/v1
         *
         * The function also accepts a full URL so previously saved
         * configurations remain compatible.
         */
        fun normalizeLmStudioAddress(raw: String): String {
            var value = raw.trim()

            if (value.isEmpty()) {
                return ""
            }

            value = value
                .removePrefix("http://")
                .removePrefix("https://")
                .trimEnd('/')

            if (value.endsWith("/v1", ignoreCase = true)) {
                value = value
                    .dropLast(3)
                    .trimEnd('/')
            }

            return "http://$value/v1"
        }

        /**
         * Converts the internally stored LM Studio URL back into the
         * user-facing IP:PORT representation.
         *
         * Example:
         *
         * http://192.168.1.10:1234/v1
         * -> 192.168.1.10:1234
         */
        fun lmStudioAddressFromUrl(raw: String): String {
            var value = raw.trim()

            if (value.isEmpty()) {
                return ""
            }

            value = value
                .removePrefix("http://")
                .removePrefix("https://")
                .trimEnd('/')

            if (value.endsWith("/v1", ignoreCase = true)) {
                value = value
                    .dropLast(3)
                    .trimEnd('/')
            }

            return value
        }
    }
}
