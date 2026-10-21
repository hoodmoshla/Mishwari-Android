package ye.mishwari

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.viewModelScope
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ye.mishwari.data.Errand
import ye.mishwari.data.MishwariDatabase

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PhotoDraftLifecycleRobolectricTest {
    private lateinit var app: Application
    private lateinit var model: MishwariViewModel
    private val created = mutableListOf<File>()

    @Before fun setup() {
        app = ApplicationProvider.getApplicationContext()
        androidx.work.testing.WorkManagerTestInitHelper.initializeTestWorkManager(app)
        runBlocking(Dispatchers.IO) {
            val dao = MishwariDatabase.get(app).dao()
            dao.allErrands().forEach { dao.deleteErrand(it.id) }
        }
        model = MishwariViewModel(app)
    }

    @After fun cleanup() {
        model.viewModelScope.cancel()
        runBlocking(Dispatchers.IO) {
            val dao = MishwariDatabase.get(app).dao()
            dao.allErrands().forEach { dao.deleteErrand(it.id) }
        }
        created.forEach { it.delete() }
    }

    private fun photoUri(): Uri {
        val output = ByteArrayOutputStream()
        Bitmap.createBitmap(3, 3, Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG, 100, output)
        val uri = Uri.parse("content://test-photos/${java.util.UUID.randomUUID()}")
        shadowOf(app.contentResolver).registerInputStream(uri, ByteArrayInputStream(output.toByteArray()))
        return uri
    }

    private fun attach(): File {
        var attached: String? = null
        model.pendingPhoto = { attached = it }
        model.importPhoto(photoUri())
        waitFor { attached != null }
        return File(requireNotNull(attached)).also { created += it; assertTrue(it.isFile) }
    }

    @Test fun explicitDiscardRemovesOnlyNewDraftPhotos() {
        val first = attach()
        val second = attach()
        model.finishPhotoEditing()
        waitFor { !first.exists() && !second.exists() }
        assertNull(model.pendingPhoto)
    }

    @Test fun successfulSaveRetainsSelectedPhotoButDeletesReplacedDraft() {
        val first = attach()
        val chosen = attach()
        model.save(Errand(id = "photo-saved", title = "صورة محفوظة", photoPath = chosen.path)) {
            model.finishPhotoEditing()
        }
        waitFor { !first.exists() && runBlocking { MishwariDatabase.get(app).dao().allErrands().any { it.id == "photo-saved" } } }
        assertTrue(chosen.isFile)
        assertEquals(chosen.path, runBlocking { MishwariDatabase.get(app).dao().allErrands().single().photoPath })
    }

    @Test fun pickerReturnAfterEditorDismissalCreatesNoFile() {
        val dir = File(app.filesDir, "photos")
        val before = dir.listFiles()?.map { it.name }?.toSet().orEmpty()
        model.pendingPhoto = { fail("A closed editor must not receive a callback") }
        model.finishPhotoEditing()
        model.importPhoto(photoUri())
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(before, dir.listFiles()?.map { it.name }?.toSet().orEmpty())
    }

    private fun waitFor(predicate: () -> Boolean) {
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15)
        do {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            if (predicate()) return
            Thread.sleep(15)
        } while (System.nanoTime() < deadline)
        fail("Timed out: ${model.error.value}")
    }
}
