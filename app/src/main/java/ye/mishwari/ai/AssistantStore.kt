package ye.mishwari.ai

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Settings and encrypted credential stored only under noBackupFilesDir (never Room or Android backup). */
class AssistantStore(
    context: Context,
    private val keyCipher: AssistantKeyCipher = AndroidKeystoreAssistantKeyCipher(),
) {
    private val directory: File = File(context.applicationContext.noBackupFilesDir, "assistant").apply {
        check(isDirectory || mkdirs()) { "تعذر تهيئة التخزين المحلي للمساعد" }
    }
    private val configFile = File(directory, "config.json")
    private val keyFile = File(directory, "api-key.enc")

    @Synchronized
    fun load(): AssistantConfig {
        val settings = readSettings()
        val endpoint = settings.optString("endpoint", "")
        val provider = settings.optString("provider", AssistantProviderIds.OPENAI_COMPATIBLE)
            .takeIf { it in SUPPORTED_PROVIDERS } ?: AssistantProviderIds.OPENAI_COMPATIBLE
        return AssistantConfig(
            endpoint = endpoint,
            model = settings.optString("model", ""),
            enabled = settings.optBoolean("enabled", false) && keyFile.isFile &&
                AssistantEndpoint.isValid(endpoint),
            hasKey = keyFile.isFile,
            provider = provider,
        )
    }

    /**
     * Saves settings and an optional replacement credential as one logical operation. Changing
     * endpoint or provider never reuses the previous key: a missing key removes it and disables
     * the assistant; a supplied valid key is encrypted and installed in this same call.
     */
    @Synchronized
    fun save(
        endpoint: String,
        model: String,
        key: String?,
        enabled: Boolean,
        provider: String = AssistantProviderIds.OPENAI_COMPATIBLE,
    ) {
        validate(endpoint, model, key, provider)

        val oldSettings = readSettings()
        val oldEndpoint = oldSettings.optString("endpoint", "")
        val oldProvider = oldSettings.optString("provider", AssistantProviderIds.OPENAI_COMPATIBLE)
            .takeIf { it in SUPPORTED_PROVIDERS } ?: AssistantProviderIds.OPENAI_COMPATIBLE
        val identityChanged = endpoint != oldEndpoint || provider != oldProvider
        val oldConfigBytes = configFile.takeIf { it.isFile }?.readBytes()
        val oldKeyBytes = keyFile.takeIf { it.isFile }?.readBytes()

        // Perform encryption before changing either on-disk file, so invalid input/Keystore errors
        // cannot destroy the last good configuration or credential.
        val encryptedReplacement = key?.takeIf { it.isNotBlank() }
            ?.toByteArray(Charsets.UTF_8)
            ?.let(keyCipher::encrypt)

        val base = JSONObject()
            .put("endpoint", endpoint)
            .put("model", model)
            .put("provider", provider)
            .put("enabled", false)

        try {
            if (identityChanged) deleteKeyFile()
            // Install the new identity in a disabled state before installing its new credential.
            // A crash in this interval cannot associate the old key with the new provider/URL.
            writeAtomically(base.toString().toByteArray(Charsets.UTF_8), configFile)

            when {
                key == null -> Unit // Preserve only when endpoint/provider identity is unchanged.
                key.isBlank() -> deleteKeyFile()
                else -> writeAtomically(requireNotNull(encryptedReplacement), keyFile)
            }

            val keyAvailable = keyFile.isFile
            val finalConfig = JSONObject()
                .put("endpoint", endpoint)
                .put("model", model)
                .put("provider", provider)
                .put("enabled", enabled && keyAvailable && endpoint.isNotBlank())
            writeAtomically(finalConfig.toString().toByteArray(Charsets.UTF_8), configFile)
        } catch (failure: Exception) {
            // Best-effort rollback keeps the previous valid files intact if any commit step fails.
            runCatching { restoreFile(configFile, oldConfigBytes) }
            runCatching { restoreFile(keyFile, oldKeyBytes) }
            throw failure
        }
    }

    @Synchronized
    fun deleteKey() {
        val settings = readSettings()
        val oldConfigBytes = configFile.takeIf { it.isFile }?.readBytes()
        val oldKeyBytes = keyFile.takeIf { it.isFile }?.readBytes()
        settings.put("enabled", false)
        try {
            // Delete first: if config writing fails, restore the key and the old settings.
            deleteKeyFile()
            writeAtomically(settings.toString().toByteArray(Charsets.UTF_8), configFile)
        } catch (failure: Exception) {
            runCatching { restoreFile(configFile, oldConfigBytes) }
            runCatching { restoreFile(keyFile, oldKeyBytes) }
            throw failure
        }
    }

    /** Returns null when no key is present or encrypted data cannot be read; never logs details. */
    @Synchronized
    fun readKey(): String? {
        if (!keyFile.isFile) return null
        return try {
            String(keyCipher.decrypt(keyFile.readBytes()), Charsets.UTF_8).also {
                check(it.isNotBlank()) { "مفتاح المساعد غير صالح" }
            }
        } catch (_: Exception) {
            val settings = readSettings()
            settings.put("enabled", false)
            writeAtomically(settings.toString().toByteArray(Charsets.UTF_8), configFile)
            deleteKeyFile()
            null
        }
    }

    private fun validate(endpoint: String, model: String, key: String?, provider: String) {
        require(provider in SUPPORTED_PROVIDERS) { "اختر مزودًا مدعومًا" }
        require(endpoint.isEmpty() || AssistantEndpoint.isValid(endpoint)) { "أدخل عنوان HTTPS صالحًا" }
        require(endpoint.length <= MAX_ENDPOINT_LENGTH && endpoint == endpoint.trim()) { "عنوان الاتصال غير صالح" }
        require(model.length <= MAX_MODEL_LENGTH && model.none { it == '\r' || it == '\n' || it == '\u0000' }) {
            "اسم النموذج غير صالح أو أطول من المسموح"
        }
        if (key != null && key.isNotBlank()) {
            require(endpoint.isNotBlank()) { "أدخل عنوان HTTPS قبل حفظ المفتاح" }
            require(key.length <= MAX_KEY_LENGTH && key.none { it == '\r' || it == '\n' || it == '\u0000' }) {
                "مفتاح API غير صالح"
            }
        }
    }

    private fun readSettings(): JSONObject = try {
        if (configFile.isFile) JSONObject(String(configFile.readBytes(), Charsets.UTF_8)) else JSONObject()
    } catch (_: Exception) {
        JSONObject()
    }

    private fun deleteKeyFile() {
        deleteFileIfPresent(keyFile)
        deleteFileIfPresent(File(directory, "api-key.enc.tmp"))
    }

    private fun deleteFileIfPresent(file: File) {
        if (file.exists()) check(file.delete() && !file.exists()) { "تعذر حذف مفتاح المساعد؛ بقي المساعد معطّلًا" }
    }

    private fun restoreFile(file: File, bytes: ByteArray?) {
        if (bytes == null) deleteFileIfPresent(file) else writeAtomically(bytes, file)
    }

    /** Writes a sibling temporary file and renames over the destination without deleting it first. */
    private fun writeAtomically(bytes: ByteArray, destination: File) {
        val temporary = File(directory, destination.name + ".tmp")
        try {
            temporary.outputStream().use { stream -> stream.write(bytes); stream.fd.sync() }
            // On the app's local filesystem rename replaces an existing destination atomically.
            // If it fails, leave the old destination untouched and report failure.
            check(temporary.renameTo(destination)) { "تعذر حفظ إعدادات المساعد" }
        } finally {
            temporary.delete()
        }
    }

    private companion object {
        const val MAX_MODEL_LENGTH = 200
        const val MAX_ENDPOINT_LENGTH = 2048
        const val MAX_KEY_LENGTH = 4096
        val SUPPORTED_PROVIDERS = setOf(
            AssistantProviderIds.OPENAI_COMPATIBLE,
            AssistantProviderIds.GEMINI,
        )
    }
}

/** AES/GCM key is generated and retained by AndroidKeyStore; only IV+ciphertext reaches disk. */
class AndroidKeystoreAssistantKeyCipher : AssistantKeyCipher {
    override fun encrypt(plaintext: ByteArray): ByteArray {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(plaintext)
        return cipher.iv + encrypted
    }

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        require(ciphertext.size > IV_LENGTH) { "بيانات المفتاح غير صالحة" }
        val iv = ciphertext.copyOfRange(0, IV_LENGTH)
        val encrypted = ciphertext.copyOfRange(IV_LENGTH, ciphertext.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(TAG_BITS, iv))
        return cipher.doFinal(encrypted)
    }

    private fun getOrCreateKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val KEY_ALIAS = "mishwari_assistant_api_key_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val IV_LENGTH = 12
        const val TAG_BITS = 128
    }
}
