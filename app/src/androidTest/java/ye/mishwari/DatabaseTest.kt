package ye.mishwari

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import ye.mishwari.data.AppSetting
import ye.mishwari.data.Errand
import ye.mishwari.data.MishwariDatabase
import ye.mishwari.data.Place

@RunWith(AndroidJUnit4::class)
class DatabaseTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "mishwari-database-test.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    private fun openDatabase() = Room.databaseBuilder(
        context,
        MishwariDatabase::class.java,
        databaseName
    ).allowMainThreadQueries().build()

    @Test
    fun crudFlowsForeignKeysAndSeparateReopenPersistence() = runBlocking {
        var db = openDatabase()
        try {
            val dao = db.dao()
            val place = Place(id = "place-1", name = "المنزل", latitude = 15.3, longitude = 44.2)
            val errand = Errand(id = "errand-1", title = "شراء خبز", placeId = place.id)
            dao.upsertPlace(place)
            dao.upsertErrand(errand)
            dao.putSetting(AppSetting("theme", "dark"))

            assertEquals(listOf(errand), dao.allErrands())
            assertEquals(listOf(place), dao.allPlaces())
            assertEquals("dark", dao.getSetting("theme"))
            assertEquals(listOf(errand), dao.observeErrands().first())
            assertEquals(listOf(place), dao.observePlaces().first())
            assertEquals(listOf(AppSetting("theme", "dark")), dao.observeSettings().first())
            assertEquals(listOf(errand), dao.errandsForPlace(place.id))
        } finally {
            db.close()
        }

        // Deliberately construct a new Room instance to verify persisted on-disk state.
        db = openDatabase()
        try {
            val dao = db.dao()
            assertEquals("شراء خبز", dao.allErrands().single().title)
            assertEquals("المنزل", dao.allPlaces().single().name)
            assertEquals("dark", dao.getSetting("theme"))
            dao.deletePlace("place-1")
            assertNull(dao.allErrands().single().placeId)
            dao.deleteErrand("errand-1")
            assertEquals(emptyList<Errand>(), dao.allErrands())
        } finally {
            db.close()
        }
    }
}
