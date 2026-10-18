package ye.mishwari

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ye.mishwari.data.AppSetting
import ye.mishwari.data.Errand
import ye.mishwari.data.MishwariDatabase
import ye.mishwari.data.Place
import ye.mishwari.data.Repository
import org.json.JSONObject
import java.io.File
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class PersistenceRobolectricTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val opened = mutableListOf<Pair<String, MishwariDatabase>>()
    private val testFiles = mutableListOf<File>()

    private fun openDatabase(): Pair<String, MishwariDatabase> {
        val name = "mishwari-robolectric-${UUID.randomUUID()}.db"
        val db = Room.databaseBuilder(context, MishwariDatabase::class.java, name)
            .allowMainThreadQueries()
            .build()
        opened += name to db
        return name to db
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
    fun daoSupportsPlaceAndErrandCrudAndSeveralErrandsPerPlace() = runBlocking {
        val (_, db) = openDatabase()
        val dao = db.dao()
        val place = Place(id = "place-market", name = "السوق")
        dao.upsertPlace(place)
        dao.putSetting(AppSetting("theme", "dark"))

        val oldest = Errand(id = "errand-old", title = "خبز", placeId = place.id, createdAt = 10L)
        val middle = Errand(id = "errand-middle", title = "حليب", placeId = place.id, createdAt = 20L)
        val newest = Errand(id = "errand-new", title = "دواء", placeId = place.id, createdAt = 30L)
        dao.upsertErrands(listOf(oldest, middle, newest))

        assertEquals(listOf(place), dao.allPlaces())
        assertEquals(listOf(newest, middle, oldest), dao.errandsForPlace(place.id))
        assertEquals("dark", dao.getSetting("theme"))

        val renamedPlace = place.copy(name = "السوق الكبير")
        val editedErrand = middle.copy(title = "حليب طازج", notes = "عبوة واحدة")
        dao.upsertPlace(renamedPlace)
        dao.upsertErrand(editedErrand)
        dao.putSetting(AppSetting("theme", "light"))
        assertEquals(listOf(renamedPlace), dao.allPlaces())
        assertEquals(editedErrand, dao.allErrands().single { it.id == middle.id })
        assertEquals("light", dao.getSetting("theme"))
        assertEquals(listOf(newest, editedErrand, oldest), dao.errandsForPlace(place.id))

        dao.deleteErrand(oldest.id)
        assertEquals(listOf(newest, editedErrand), dao.errandsForPlace(place.id))

        // Room's declared ON DELETE SET NULL keeps errands while detaching their place.
        dao.deletePlace(place.id)
        assertTrue(dao.allPlaces().isEmpty())
        assertTrue(dao.allErrands().all { it.placeId == null })
        dao.deleteErrand(newest.id)
        dao.deleteErrand(middle.id)
        assertTrue(dao.allErrands().isEmpty())
    }

    @Test
    fun separateDatabaseFilesAreCompletelyIsolatedEvenForMatchingIds() = runBlocking {
        val (_, firstDb) = openDatabase()
        val (_, secondDb) = openDatabase()
        val firstPlace = Place(id = "shared-id", name = "قاعدة أولى")
        val secondPlace = Place(id = "shared-id", name = "قاعدة ثانية")

        firstDb.dao().upsertPlace(firstPlace)
        secondDb.dao().upsertPlace(secondPlace)
        firstDb.dao().upsertErrand(Errand(id = "same-errand", title = "أول", placeId = firstPlace.id))

        assertEquals(listOf(firstPlace), firstDb.dao().allPlaces())
        assertEquals(listOf(secondPlace), secondDb.dao().allPlaces())
        assertEquals(1, firstDb.dao().allErrands().size)
        assertTrue(secondDb.dao().allErrands().isEmpty())
        assertNull(secondDb.dao().getSetting("theme"))
    }

    @Test
    fun closingAndReopeningDatabaseRetainsDataAndForeignKeyBehavior() = runBlocking {
        val (databaseName, firstDb) = openDatabase()
        val place = Place(id = "home", name = "المنزل", latitude = 15.3, longitude = 44.2)
        val errand = Errand(id = "bread", title = "شراء خبز", placeId = place.id, createdAt = 123L)
        firstDb.dao().upsertPlace(place)
        firstDb.dao().upsertErrand(errand)
        firstDb.dao().putSetting(AppSetting("theme", "dark"))
        firstDb.close()
        opened.removeAll { it.first == databaseName }

        val reopened = Room.databaseBuilder(context, MishwariDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        opened += databaseName to reopened
        val dao = reopened.dao()
        assertEquals(listOf(place), dao.allPlaces())
        assertEquals(listOf(errand), dao.allErrands())
        assertEquals(listOf(errand), dao.errandsForPlace(place.id))
        assertEquals("dark", dao.getSetting("theme"))

        dao.deletePlace(place.id)
        assertNull(dao.allErrands().single().placeId)
    }

    @Test
    fun bundledDirectoryImportsAll358AdministrativePlaces() = runBlocking {
        val (_, db) = openDatabase()
        val repository = Repository(db)
        val directory = context.assets.open("yemen-directory.json").bufferedReader(Charsets.UTF_8).use { it.readText() }

        assertEquals(358, repository.importDirectory(directory))
        val places = db.dao().allPlaces()
        assertEquals(358, places.size)
        assertEquals(22, places.count { it.kind == "governorate" })
        assertEquals(335, places.count { it.kind == "district" })
        assertEquals(1, places.count { it.kind == "country" })
        assertTrue(places.none { it.latitude != null || it.longitude != null || it.monitored })

        // Re-importing is additive/idempotent for the stable IDs in this bundled file.
        assertEquals(358, repository.importDirectory(directory))
        assertEquals(358, db.dao().allPlaces().size)
    }

    @Test
    fun backupRoundTripPreservesRecordsAndSettingsButEmbedsOnlySafeLocalPhotos() = runBlocking {
        val (_, sourceDb) = openDatabase()
        val source = Repository(sourceDb)
        val place = Place(id = "backup-place", name = "المتجر", latitude = 15.3, longitude = 44.2, monitored = true)
        source.savePlace(place)
        source.setSetting("theme", "dark")
        source.setSetting("travel_place", place.id)
        source.setSetting("geo_registration_fingerprint", "forged-worker-state")

        val photoBytes = byteArrayOf(1, 2, 3, 4, 5)
        val photosDirectory = File(context.filesDir, "photos").apply { mkdirs() }
        val safePhoto = File(photosDirectory, "test-${UUID.randomUUID()}.bin").apply { writeBytes(photoBytes) }
        val outsidePhoto = File(context.cacheDir, "outside-${UUID.randomUUID()}.bin").apply { writeBytes(byteArrayOf(9, 8, 7)) }
        testFiles += safePhoto
        testFiles += outsidePhoto
        val safeErrand = Errand(id = "safe-photo", title = "صورة محلية", placeId = place.id, photoPath = safePhoto.absolutePath)
        val outsideErrand = Errand(id = "outside-photo", title = "مسار خارجي", placeId = place.id, photoPath = outsidePhoto.absolutePath)
        source.saveErrand(safeErrand)
        source.saveErrand(outsideErrand)

        val backup = source.exportBackup(context)
        val backupErrands = JSONObject(backup).getJSONArray("errands")
        val embeddedSafePhoto = (0 until backupErrands.length()).map { backupErrands.getJSONObject(it) }
            .single { it.getString("id") == safeErrand.id }
        val excludedOutsidePhoto = (0 until backupErrands.length()).map { backupErrands.getJSONObject(it) }
            .single { it.getString("id") == outsideErrand.id }
        assertTrue(embeddedSafePhoto.has("photoBase64"))
        assertFalse(excludedOutsidePhoto.has("photoBase64"))

        val (_, targetDb) = openDatabase()
        val target = Repository(targetDb)
        assertEquals(2, target.importBackup(context, backup))
        assertEquals("dark", targetDb.dao().getSetting("theme"))
        assertEquals("", targetDb.dao().getSetting("travel_place"))
        assertNull(targetDb.dao().getSetting("geo_registration_fingerprint"))
        assertFalse(targetDb.dao().allPlaces().single().monitored)
        val importedErrands = targetDb.dao().allErrands().associateBy { it.id }
        val importedSafePhoto = File(requireNotNull(importedErrands.getValue(safeErrand.id).photoPath))
        testFiles += importedSafePhoto
        assertArrayEquals(photoBytes, importedSafePhoto.readBytes())
        assertNull(importedErrands.getValue(outsideErrand.id).photoPath)
    }

    @Test
    fun invalidBackupWithMissingParentOrCycleDoesNotPartiallyModifyExistingRows() = runBlocking {
        val (_, db) = openDatabase()
        val repository = Repository(db)
        val existing = Place(id = "existing-place", name = "مكان محفوظ")
        repository.savePlace(existing)

        val missingParentBackup = JSONObject()
            .put("schema", 1)
            .put("places", org.json.JSONArray().put(JSONObject()
                .put("id", "orphan")
                .put("name", "مكان يتيم")
                .put("parentId", "does-not-exist")))
            .put("errands", org.json.JSONArray())
            .put("settings", org.json.JSONArray())
        val cycleBackup = JSONObject()
            .put("schema", 1)
            .put("places", org.json.JSONArray()
                .put(JSONObject().put("id", "cycle-a").put("name", "أ").put("parentId", "cycle-b"))
                .put(JSONObject().put("id", "cycle-b").put("name", "ب").put("parentId", "cycle-a")))
            .put("errands", org.json.JSONArray())
            .put("settings", org.json.JSONArray())

        listOf(missingParentBackup, cycleBackup).forEach { invalid ->
            var rejected = false
            try {
                repository.importBackup(context, invalid.toString())
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue("Malformed hierarchy should be rejected", rejected)
            assertEquals(listOf(existing), db.dao().allPlaces())
            assertTrue(db.dao().allErrands().isEmpty())
        }
    }
}
