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
        return AssistantConfig(
            endpoint = settings.optString("endpoint", ""),
            model = settings.optString("model", ""),
            enabled = settings.optBoolean("enabled", false) && keyFile.isFile &&
                AssistantEndpoint.isValid(settings.optString("endpoint", "")),
            hasKey = keyFile.isFile,
        )
    }

    /**
     * Saves settings. A changed endpoint is persisted disabled and without a key; a new key
     * must be supplied in a subsequent save, preventing an old credential crossing providers.
     * A null key leaves the current key unchanged; pass blank to remove it (or call deleteKey).
     */
    @Synchronized
    fun save(endpoint: String, model: String, key: String?, enabled: Boolean) {
        validateEndpointForStorage(endpoint)
        require(model.length <= MAX_MODEL_LENGTH) { "اسم النموذج أطول من المسموح" }
        val currentSettings = readSettings()
        val previous = currentSettings.optString("endpoint", "")
        val changedEndpoint = endpoint != previous && (previous.isNotEmpty() || keyFile.exists())
        if (changedEndpoint) {
            currentSettings.put("enabled", false)
            writeAtomically(configFile, currentSettings.toString().toByteArray(Charsets.UTF_8))
            deleteKeyFile()
        }

        if (!changedEndpoint && key != null) {
            if (key.isBlank()) {
                currentSettings.put("enabled", false)
                writeAtomically(configFile, currentSettings.toString().toByteArray(Charsets.UTF_8))
                deleteKeyFile()
            } else {
                require(endpoint.isNotBlank()) { "أدخل عنوان HTTPS قبل حفظ المفتاح" }
                require(key.length <= MAX_KEY_LENGTH && key.none { it == '\r' || it == '\n' || it == '\u0000' }) {
                    "مفتاح API غير صالح"
                }
                currentSettings.put("enabled", false)
                writeAtomically(configFile, currentSettings.toString().toByteArray(Charsets.UTF_8))
                writeAtomically(keyFile, keyCipher.encrypt(key.toByteArray(Charsets.UTF_8)))
            }
        }

        val keyAvailable = keyFile.isFile
        val json = JSONObject()
            .put("endpoint", endpoint)
            .put("model", model)
            .put("enabled", !changedEndpoint && enabled && keyAvailable && endpoint.isNotBlank())
        writeAtomically(configFile, json.toString().toByteArray(Charsets.UTF_8))
    }

    @Synchronized
    fun deleteKey() {
        val settings = readSettings()
        settings.put("enabled", false)
        writeAtomically(configFile, settings.toString().toByteArray(Charsets.UTF_8))
        deleteKeyFile()
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
            writeAtomically(configFile, settings.toString().toByteArray(Charsets.UTF_8))
            deleteKeyFile()
            null
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

    private fun writeAtomically(destination: File, bytes: ByteArray) {
        val temporary = File(directory, destination.name + ".tmp")
        try {
            temporary.outputStream().use { it.write(bytes); it.fd.sync() }
            if (!temporary.renameTo(destination)) {
                if (destination.exists()) check(destination.delete()) { "تعذر حفظ إعدادات المساعد" }
                check(temporary.renameTo(destination)) { "تعذر حفظ إعدادات المساعد" }
            }
        } finally {
            temporary.delete()
        }
    }

    private fun validateEndpointForStorage(endpoint: String) {
        require(endpoint.isEmpty() || AssistantEndpoint.isValid(endpoint)) { "أدخل عنوان HTTPS صالحًا" }
    }

    private companion object {
        const val MAX_MODEL_LENGTH = 200
        const val MAX_KEY_LENGTH = 4096
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
