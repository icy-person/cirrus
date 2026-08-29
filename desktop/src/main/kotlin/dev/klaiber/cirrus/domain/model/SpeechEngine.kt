package dev.klaiber.cirrus.domain.model

/**
 * How much of an answer gets read aloud.
 *
 * The default is a summary rather than the whole thing, and the reason is that speech is linear.
 * A written answer is built to be skimmed — headings to jump between, a table to glance at, a code
 * block to ignore — and none of that survives being spoken at two and a half words a second. Read
 * in full, an ordinary answer here is six minutes of audio with no way to skip the part you did not
 * need, which is why people press play and then stop it a minute in.
 *
 * [FULL] is still here because there is one case the summary cannot serve: somebody listening to
 * their own text to check it. That person wants every word, and no summariser should get a vote.
 */
enum class ReadAloudMode(val label: String, val description: String) {
    SUMMARY(
        label = "Spoken summary",
        description = "A minute or so covering what the answer concluded and why. Short answers " +
            "are still read in full.",
    ),
    FULL(
        label = "The whole answer",
        description = "Every word, including lists and tables. Long answers take a long time.",
    ),
}

/** Who does the talking when an answer is read aloud. Kept for shape compatibility. */
enum class SpeechEngine(val label: String, val description: String) {
    DEVICE(
        label = "On device",
        description = "The platform's own voice. Free, offline, and always available.",
    ),
    ELEVENLABS(
        label = "ElevenLabs",
        description = "Far more natural, but it needs an API key and spends characters.",
    ),
}

/**
 * The ElevenLabs models worth offering.
 */
enum class ElevenLabsModel(val id: String, val label: String, val description: String) {
    FLASH(
        id = "eleven_flash_v2_5",
        label = "Flash v2.5",
        description = "Lowest latency. Best for reading a long answer back.",
    ),
    MULTILINGUAL(
        id = "eleven_multilingual_v2",
        label = "Multilingual v2",
        description = "The stable, high-quality workhorse. Slower to start.",
    ),
    EXPRESSIVE(
        id = "eleven_v3",
        label = "v3",
        description = "The most expressive delivery, and the slowest.",
    );

    companion object {
        val Default = FLASH

        fun fromId(id: String): ElevenLabsModel = entries.firstOrNull { it.id == id } ?: Default
    }
}
