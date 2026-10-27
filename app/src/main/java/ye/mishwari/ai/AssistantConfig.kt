package ye.mishwari.ai

/** Stable identifiers stored with the assistant settings. */
object AssistantProviderIds {
    const val OPENAI_COMPATIBLE = "openai-compatible"
    const val GEMINI = "gemini"
}

/** User-editable OpenAI-compatible endpoint and model settings. */
data class AssistantConfig(
    val endpoint: String,
    val model: String,
    val enabled: Boolean,
    val hasKey: Boolean,
    val provider: String = AssistantProviderIds.OPENAI_COMPATIBLE,
)

/** A small seam so JVM tests can exercise persistence without Android Keystore. */
interface AssistantKeyCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}
