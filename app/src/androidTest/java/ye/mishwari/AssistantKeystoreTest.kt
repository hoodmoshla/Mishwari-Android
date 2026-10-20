package ye.mishwari

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import ye.mishwari.ai.AssistantStore
import ye.mishwari.data.MishwariDatabase
import ye.mishwari.data.Repository
import java.io.File

/** Uses AndroidKeyStore on a device/emulator, with invented credentials only. */
@RunWith(AndroidJUnit4::class)
class AssistantKeystoreTest {
    @Test fun nativeKeystoreRoundTripReplacementDeletionAndBackupExclusion() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "assistant")
        directory.deleteRecursively()
        val db = Room.inMemoryDatabaseBuilder(context, MishwariDatabase::class.java).build()
        try {
            val store = AssistantStore(context)
            assertFalse(store.load().enabled)
            assertFalse(store.load().hasKey)
            val fakeFirst = "invented-native-test-key-first"
            val fakeSecond = "invented-native-test-key-second"
            store.save("https://example.invalid/v1/chat/completions", "fake-model", fakeFirst, true)
            assertEquals(fakeFirst, store.readKey())
            store.save("https://example.invalid/v1/chat/completions", "fake-model", fakeSecond, true)
            assertEquals(fakeSecond, AssistantStore(context).readKey())
            val persisted = directory.listFiles()!!.map { it.readBytes().toString(Charsets.ISO_8859_1) }.joinToString()
            assertFalse(persisted.contains(fakeFirst))
            assertFalse(persisted.contains(fakeSecond))
            val backup = Repository(db).exportBackup(context)
            assertFalse(backup.contains(fakeFirst))
            assertFalse(backup.contains(fakeSecond))
            assertFalse(backup.contains("example.invalid"))
            store.save("https://other.invalid/custom-completion", "", null, true)
            assertFalse(store.load().enabled)
            assertFalse(store.load().hasKey)
            assertNull(store.readKey())
            store.save("https://other.invalid/custom-completion", "", fakeFirst, true)
            assertEquals(fakeFirst, store.readKey())
            store.deleteKey()
            assertFalse(store.load().enabled)
            assertFalse(store.load().hasKey)
            assertNull(store.readKey())
        } finally {
            db.close()
            directory.deleteRecursively()
        }
    }
}
