package ye.mishwari.data

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RepositoryBatchSafetyRobolectricTest {
    private val context: Application = ApplicationProvider.getApplicationContext()

    @Test
    fun batchValidatesAllDraftsBeforeOneAtomicWrite() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MishwariDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repository = Repository(db)
            val place = Place(id = "batch-place", name = "سوق الدفعة")
            repository.savePlace(place)
            val first = Errand(id = "batch-first", title = "حليب", placeId = place.id)
            val invalid = Errand(id = "batch-invalid", title = "", placeId = place.id)

            assertTrue(runCatching { repository.saveErrandsBatch(listOf(first, invalid)) }.isFailure)
            assertTrue("a rejected batch must not leave a partial row", db.dao().allErrands().isEmpty())

            repository.saveErrandsBatch(listOf(first, Errand(id = "batch-second", title = "خبز", placeId = place.id)))
            assertEquals(listOf("batch-first", "batch-second"), db.dao().allErrands().map { it.id }.sorted())
        } finally {
            db.close()
        }
    }

    @Test
    fun reopeningPlaceUpdatesOnlyLinkedCompletedRowsAndNeverDuplicates() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MishwariDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repository = Repository(db)
            repository.savePlace(Place(id = "reopen-place", name = "المكان المحدد"))
            repository.savePlace(Place(id = "reopen-other", name = "مكان آخر"))
            repository.saveErrandsBatch(listOf(
                Errand(id = "reopen-target", title = "طلب مكتمل", placeId = "reopen-place", status = "مكتمل"),
                Errand(id = "reopen-active", title = "طلب نشط", placeId = "reopen-place"),
                Errand(id = "reopen-other-task", title = "طلب في مكان آخر", placeId = "reopen-other", status = "مكتمل"),
            ))

            assertEquals(1, repository.reopenCompletedAtPlace("reopen-place"))
            val rows = db.dao().allErrands().associateBy { it.id }
            assertEquals("جديد", rows.getValue("reopen-target").status)
            assertEquals("جديد", rows.getValue("reopen-active").status)
            assertEquals("مكتمل", rows.getValue("reopen-other-task").status)
            assertEquals(3, rows.size)
        } finally {
            db.close()
        }
    }

    @Test
    fun checkboxToggleUpdatesLatestStoredStatusWithoutCreatingAnotherErrand() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, MishwariDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        try {
            val repository = Repository(db)
            repository.saveErrand(Errand(id = "checkbox-task", title = "طلب checkbox"))

            repository.toggleErrandCompletion("checkbox-task")
            assertEquals("مكتمل", db.dao().allErrands().single().status)
            repository.toggleErrandCompletion("checkbox-task")

            val rows = db.dao().allErrands()
            assertEquals(1, rows.size)
            assertEquals("جديد", rows.single().status)
        } finally {
            db.close()
        }
    }
}
