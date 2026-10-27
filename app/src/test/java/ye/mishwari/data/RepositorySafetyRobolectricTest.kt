package ye.mishwari.data

import android.app.Application
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import android.util.Base64
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class RepositorySafetyRobolectricTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<Pair<String, MishwariDatabase>>()
    private val testFiles = mutableListOf<File>()

    private fun openDatabase(): Pair<String, MishwariDatabase> {
        val name = "mishwari-safety-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, MishwariDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        opened += name to db
        return name to db
    }

    private fun photo(bytes: ByteArray, suffix: String = "img"): File {
        val folder = File(context.filesDir, "photos").apply { mkdirs() }
        return File(folder, "${UUID.randomUUID()}.$suffix").apply {
            writeBytes(bytes)
            testFiles += this
        }
    }

    @After
    fun closeAndDeleteDatabases() {
        opened.forEach { (name, db) ->
            db.close()
            context.deleteDatabase(name)
        }
        opened.clear()
        testFiles.forEach { it.delete() }
        testFiles.clear()
    }

    @Test
    fun schemaV2DatabaseReopensWithRowsIntactAndUnsupportedDowngradeDoesNotDropThem() = runBlocking {
        val (name, db) = openDatabase()
        val place = Place(id = "v1-place", name = "مخزن محفوظ")
        val errand = Errand(id = "v1-errand", title = "طلب محفوظ", placeId = place.id, photoPath = "/legacy/photo.img")
        db.dao().upsertPlace(place)
        db.dao().upsertErrand(errand)
        db.dao().putSetting(AppSetting("theme", "dark"))
        assertEquals(2, db.openHelper.readableDatabase.version)
        db.close()
        opened.removeAll { it.first == name }

        val reopened = Room.databaseBuilder(context, MishwariDatabase::class.java, name)
            .allowMainThreadQueries().build()
        opened += name to reopened
        assertEquals(listOf(place), reopened.dao().allPlaces())
        assertEquals(listOf(errand), reopened.dao().allErrands())
        assertEquals("dark", reopened.dao().getSetting("theme"))
        reopened.close()
        opened.removeAll { it.first == name }

        // Mark the existing v2 file as a future version: the v2 app has no supported downgrade.
        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READWRITE)
            .use { it.version = 3 }
        val unsupported = Room.databaseBuilder(context, MishwariDatabase::class.java, name)
            .allowMainThreadQueries().build()
        val failure = runCatching { unsupported.openHelper.writableDatabase }.exceptionOrNull()
        unsupported.close()
        assertNotNull("Opening a newer unsupported schema must be rejected, not destructively reset", failure)

        SQLiteDatabase.openDatabase(context.getDatabasePath(name).path, null, SQLiteDatabase.OPEN_READONLY).use { raw ->
            assertEquals(3, raw.version)
            raw.rawQuery("SELECT id FROM places WHERE id = 'v1-place'", null).use { cursor -> assertTrue(cursor.moveToFirst()) }
            raw.rawQuery("SELECT id FROM errands WHERE id = 'v1-errand'", null).use { cursor -> assertTrue(cursor.moveToFirst()) }
            raw.rawQuery("SELECT value FROM app_settings WHERE `key` = 'theme'", null).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("dark", cursor.getString(0))
            }
        }
    }

    @Test
    fun backupRoundTripEmbedsSeveralErrandPhotosAndCopiesSharedPhotoPerRequest() = runBlocking {
        val (_, sourceDb) = openDatabase()
        val source = Repository(sourceDb)
        val place = Place(id = "photo-place", name = "المتجر")
        source.savePlace(place)
        val shared = photo(byteArrayOf(1, 2, 3, 4))
        val second = photo(byteArrayOf(5, 6, 7))
        source.saveErrand(Errand(id = "photo-a", title = "صورة أولى", placeId = place.id, photoPath = shared.absolutePath, actualPrice = "33.50"))
        source.saveErrand(Errand(id = "photo-b", title = "الصورة المشتركة", placeId = place.id, photoPath = shared.absolutePath))
        source.saveErrand(Errand(id = "photo-c", title = "صورة ثانية", placeId = place.id, photoPath = second.absolutePath))

        val backup = JSONObject(source.exportBackup(context))
        val rows = backup.getJSONArray("errands")
        assertEquals(3, rows.length())
        assertTrue((0 until rows.length()).all { rows.getJSONObject(it).has("photoBase64") })
        assertTrue((0 until rows.length()).none { rows.getJSONObject(it).has("photoPath") })
        val backedUpPhotoA = (0 until rows.length()).map(rows::getJSONObject).single { it.getString("id") == "photo-a" }
        assertEquals("33.50", backedUpPhotoA.getString("actualPrice"))

        val (_, targetDb) = openDatabase()
        val target = Repository(targetDb)
        assertEquals(3, target.importBackup(context, backup.toString()))
        val imported = targetDb.dao().allErrands().associateBy { it.id }
        val importedA = File(requireNotNull(imported.getValue("photo-a").photoPath))
        val importedB = File(requireNotNull(imported.getValue("photo-b").photoPath))
        val importedC = File(requireNotNull(imported.getValue("photo-c").photoPath))
        testFiles += listOf(importedA, importedB, importedC)
        assertArrayEquals(shared.readBytes(), importedA.readBytes())
        assertArrayEquals(shared.readBytes(), importedB.readBytes())
        assertArrayEquals(second.readBytes(), importedC.readBytes())
        assertEquals("33.50", imported.getValue("photo-a").actualPrice)
        assertFalse("Each imported request receives its own managed file", importedA.canonicalPath == importedB.canonicalPath)
    }

    @Test
    fun legacyPathOnlyBackupPreservesExistingLocalAttachmentAndNeverImportsForeignPaths() = runBlocking {
        val (_, db) = openDatabase()
        val repository = Repository(db)
        val localPhoto = photo(byteArrayOf(10, 20, 30))
        repository.saveErrand(Errand(id = "legacy-match", title = "طلب محلي", photoPath = localPhoto.path))
        val legacy = JSONObject().put("schema", 1).put("places", JSONArray()).put("settings", JSONArray())
            .put("errands", JSONArray()
                .put(JSONObject().put("id", "legacy-match").put("title", "عنوان النسخة القديمة").put("photoPath", "/another-device/private/old.img"))
                .put(JSONObject().put("id", "legacy-new").put("title", "طلب جهاز آخر").put("photoPath", localPhoto.path)))
        assertEquals(2, repository.importBackup(context, legacy.toString(), emptySet()))
        val restored = db.dao().allErrands().associateBy { it.id }
        assertEquals(localPhoto.path, restored.getValue("legacy-match").photoPath)
        assertEquals("عنوان النسخة القديمة", restored.getValue("legacy-match").title)
        assertEquals("", restored.getValue("legacy-match").actualPrice)
        assertTrue(localPhoto.exists())
        assertNull(restored.getValue("legacy-new").photoPath)
        assertEquals("", restored.getValue("legacy-new").actualPrice)
    }

    @Test
    fun databaseFailureRollsBackRowsAndRemovesEveryPhotoCreatedForThatImport() = runBlocking {
        val (_, db) = openDatabase()
        val repository = Repository(db)
        val place = Place(id = "old-place", name = "قديم")
        val original = Errand(id = "old-errand", title = "قديم", placeId = place.id)
        repository.savePlace(place)
        repository.saveErrand(original)
        db.openHelper.writableDatabase.execSQL(
            "CREATE TRIGGER reject_import BEFORE INSERT ON errands BEGIN SELECT RAISE(ABORT, 'test failure'); END"
        )
        val photosDir = File(context.filesDir, "photos")
        val before = photosDir.listFiles().orEmpty().map { it.canonicalPath }.toSet()
        val bytes = byteArrayOf(9, 8, 7, 6)
        val backup = JSONObject()
            .put("schema", 1)
            .put("places", JSONArray().put(JSONObject().put("id", "import-place").put("name", "مستورد")))
            .put("errands", JSONArray().put(
                JSONObject().put("id", "import-errand").put("title", "طلب صورة")
                    .put("placeId", "import-place").put("photoBase64", Base64.encodeToString(bytes, Base64.NO_WRAP))
            ))
            .put("settings", JSONArray())

        assertTrue(runCatching { repository.importBackup(context, backup.toString()) }.isFailure)
        assertEquals(listOf(place), db.dao().allPlaces())
        assertEquals(listOf(original), db.dao().allErrands())
        assertEquals(before, photosDir.listFiles().orEmpty().map { it.canonicalPath }.toSet())
    }

    @Test
    fun exportRejectsMissingOrOversizedManagedPhotoInsteadOfSilentlyDroppingIt() = runBlocking {
        val (_, db) = openDatabase()
        val repository = Repository(db)
        val missing = File(File(context.filesDir, "photos"), "${UUID.randomUUID()}.img")
        repository.saveErrand(Errand(id = "missing-photo", title = "ملف مفقود", photoPath = missing.absolutePath))
        assertTrue(runCatching { repository.exportBackup(context) }.exceptionOrNull() is IllegalArgumentException)

        repository.removeErrand(Errand(id = "missing-photo", title = "ملف مفقود"))
        val oversized = photo(ByteArray(5 * 1024 * 1024 + 1))
        repository.saveErrand(Errand(id = "large-photo", title = "ملف كبير", photoPath = oversized.absolutePath))
        assertTrue(runCatching { repository.exportBackup(context) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun exportRejectsOversizedAggregateWhileRetainingEveryOriginalAttachment() = runBlocking {
        val (_, db) = openDatabase()
        val repository = Repository(db)
        val shared = photo(ByteArray(4 * 1024 * 1024) { 7 })
        repeat(5) { index -> repository.saveErrand(Errand(id = "large-$index", title = "صورة $index", photoPath = shared.path)) }
        assertTrue(runCatching { repository.exportBackup(context) }.exceptionOrNull() is IllegalArgumentException)
        assertEquals(5, db.dao().allErrands().size)
        assertTrue(shared.exists())
    }

    @Test
    fun targetedPhotoCleanupPreservesDatabaseSharedAndActiveDraftPaths() = runBlocking {
        val (_, db) = openDatabase()
        val repository = Repository(db)
        val shared = photo(byteArrayOf(1), "img")
        val draft = photo(byteArrayOf(2), "img")
        val orphan = photo(byteArrayOf(3), "bin")
        repository.saveErrand(Errand(id = "uses-shared-a", title = "أ", photoPath = shared.absolutePath))
        repository.saveErrand(Errand(id = "uses-shared-b", title = "ب", photoPath = shared.absolutePath))

        assertEquals(1, repository.cleanupPhotoCandidates(context, listOf(shared.absolutePath, draft.absolutePath, orphan.absolutePath), setOf(draft.absolutePath)))
        assertTrue(shared.exists())
        assertTrue(draft.exists())
        assertFalse(orphan.exists())

        // A save/remove cleanup call runs only after the DB transaction, and must retain shared files.
        repository.removeErrand(Errand(id = "uses-shared-a", title = "أ"), context, emptySet())
        assertTrue(shared.exists())
        repository.removeErrand(Errand(id = "uses-shared-b", title = "ب"), context, emptySet())
        assertFalse(shared.exists())
    }

    @Test
    fun monitoringCannotBeEnabledWithoutCoordinatesButPlaceCanStillBeSaved() = runBlocking {
        val (_, db) = openDatabase()
        val repository = Repository(db)
        val administrativePlace = Place(id = "no-coordinate", name = "اسم إداري فقط")
        repository.savePlace(administrativePlace)
        assertTrue(runCatching { repository.savePlace(administrativePlace.copy(monitored = true)) }.isFailure)
        assertEquals(listOf(administrativePlace), db.dao().allPlaces())
        repository.savePlace(administrativePlace.copy(latitude = 15.0, longitude = 44.0, monitored = true))
        assertTrue(db.dao().allPlaces().single().monitored)
    }

    @Test
    fun deletingParentDetachesChildrenInTransactionAndPreservesNestedRowsAndErrands() = runBlocking {
        val (_, db) = openDatabase()
        val repository = Repository(db)
        val root = Place(id = "root", name = "الأب")
        val child = Place(id = "child", name = "الابن", parentId = root.id)
        val grandchild = Place(id = "grandchild", name = "الحفيد", parentId = child.id)
        repository.savePlace(root)
        repository.savePlace(child)
        repository.savePlace(grandchild)
        repository.saveErrand(Errand(id = "root-task", title = "طلب الأب", placeId = root.id))
        repository.saveErrand(Errand(id = "child-task", title = "طلب الابن", placeId = child.id))

        repository.removePlace(root)
        val remaining = db.dao().allPlaces().associateBy { it.id }
        assertFalse(remaining.containsKey(root.id))
        assertNull(remaining.getValue(child.id).parentId)
        assertEquals(child.id, remaining.getValue(grandchild.id).parentId)
        val errands = db.dao().allErrands().associateBy { it.id }
        assertNull(errands.getValue("root-task").placeId)
        assertEquals(child.id, errands.getValue("child-task").placeId)
    }
}
