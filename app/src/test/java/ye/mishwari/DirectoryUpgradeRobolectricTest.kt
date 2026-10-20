package ye.mishwari

import android.app.Application
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import ye.mishwari.data.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class DirectoryUpgradeRobolectricTest {
    @Test fun firstOpenAfterUpgradeAddsMissingDirectoryWithoutReplacingExistingData() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val oldDb = Room.databaseBuilder(context, MishwariDatabase::class.java, "mishwari.db").build()
        val previousOfficial = Place(id = "YE11", name = "اسم محلي محفوظ", kind = "governorate", latitude = 15.2, longitude = 44.1, monitored = true)
        val custom = Place(id = "personal-shop", name = "متجري الخاص")
        val errand = Errand(id = "old-errand", title = "طلب الإصدار السابق", placeId = custom.id)
        oldDb.dao().upsertPlace(previousOfficial)
        oldDb.dao().upsertPlace(custom)
        oldDb.dao().upsertErrand(errand)
        oldDb.close()
        val upgraded = MishwariDatabase.get(context)
        val all = upgraded.dao().allPlaces()
        assertEquals(359, all.size)
        assertEquals(previousOfficial, all.single { it.id == "YE11" })
        assertEquals(custom, all.single { it.id == custom.id })
        assertEquals(errand, upgraded.dao().allErrands().single())
        assertEquals(335, all.count { it.kind == "district" })
        assertEquals(22, all.count { it.kind == "governorate" })
    }
}
