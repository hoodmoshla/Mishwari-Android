package ye.mishwari.ai

/** User-editable OpenAI-compatible endpoint and model settings. */
data class AssistantConfig(
    val endpoint: String,
    val model: String,
    val enabled: Boolean,
    val hasKey: Boolean,
)

/** A small seam so JVM tests can exercise persistence without Android Keystore. */
interface AssistantKeyCipher {
    fun encrypt(plaintext: ByteArray): ByteArray
    fun decrypt(ciphertext: ByteArray): ByteArray
}
