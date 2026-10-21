package ye.mishwari

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import ye.mishwari.data.AppSetting
import ye.mishwari.data.Errand
import ye.mishwari.data.MishwariDatabase
import ye.mishwari.data.Place

/** Opens a hand-built, schema-export-identical v1 database so this is not just an in-memory CRUD test. */
@RunWith(AndroidJUnit4::class)
class DatabaseUpgradeSafetyTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val databaseName = "mishwari-schema-v1-fixture.db"

    @After
    fun cleanUp() {
        context.deleteDatabase(databaseName)
    }

    @Test
    fun currentRoomOpensSchemaV1FixtureAndPreservesUserRows() = runBlocking {
        val file = context.getDatabasePath(databaseName)
        file.parentFile?.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(file, null).use { fixture ->
            fixture.execSQL(
                """CREATE TABLE IF NOT EXISTS `places` (`id` TEXT NOT NULL, `name` TEXT NOT NULL, `parentId` TEXT, `kind` TEXT NOT NULL, `governorate` TEXT NOT NULL, `district` TEXT NOT NULL, `area` TEXT NOT NULL, `detail` TEXT NOT NULL, `latitude` REAL, `longitude` REAL, `radiusMeters` REAL NOT NULL, `monitored` INTEGER NOT NULL, `source` TEXT NOT NULL, `sourceUpdated` TEXT NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`parentId`) REFERENCES `places`(`id`) ON UPDATE CASCADE ON DELETE RESTRICT)"""
            )
            fixture.execSQL("CREATE INDEX IF NOT EXISTS `index_places_parentId` ON `places` (`parentId`)")
            fixture.execSQL(
                """CREATE TABLE IF NOT EXISTS `errands` (`id` TEXT NOT NULL, `title` TEXT NOT NULL, `description` TEXT NOT NULL, `placeId` TEXT, `category` TEXT NOT NULL, `quantity` TEXT NOT NULL, `expectedPrice` TEXT NOT NULL, `notes` TEXT NOT NULL, `priority` INTEGER NOT NULL, `dueDate` TEXT NOT NULL, `photoPath` TEXT, `status` TEXT NOT NULL, `createdAt` INTEGER NOT NULL, PRIMARY KEY(`id`), FOREIGN KEY(`placeId`) REFERENCES `places`(`id`) ON UPDATE CASCADE ON DELETE SET NULL)"""
            )
            fixture.execSQL("CREATE INDEX IF NOT EXISTS `index_errands_placeId` ON `errands` (`placeId`)")
            fixture.execSQL("CREATE TABLE IF NOT EXISTS `app_settings` (`key` TEXT NOT NULL, `value` TEXT NOT NULL, PRIMARY KEY(`key`))")
            fixture.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)")
            fixture.execSQL(
                "INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, 'ea8861cf4ffcc37d925dc708fe91b464')"
            )
            fixture.insertOrThrow("places", null, ContentValues().apply {
                put("id", "fixture-place")
                put("name", "مكان المستخدم")
                putNull("parentId")
                put("kind", "custom")
                put("governorate", "")
                put("district", "")
                put("area", "")
                put("detail", "")
                putNull("latitude")
                putNull("longitude")
                put("radiusMeters", 500f)
                put("monitored", 0)
                put("source", "يدوي")
                put("sourceUpdated", "")
            })
            fixture.insertOrThrow("errands", null, ContentValues().apply {
                put("id", "fixture-errand")
                put("title", "طلب المستخدم")
                put("description", "وصف محفوظ")
                put("placeId", "fixture-place")
                put("category", "مشتريات")
                put("quantity", "2")
                put("expectedPrice", "100")
                put("notes", "ملاحظة محفوظة")
                put("priority", 1)
                put("dueDate", "")
                putNull("photoPath")
                put("status", "جديد")
                put("createdAt", 123456L)
            })
            fixture.insertOrThrow("app_settings", null, ContentValues().apply {
                put("key", "theme")
                put("value", "dark")
            })
            fixture.version = 1
        }

        val current = Room.databaseBuilder(context, MishwariDatabase::class.java, databaseName)
            .allowMainThreadQueries()
            .build()
        try {
                val dao = current.dao()
                assertEquals(1, current.openHelper.readableDatabase.version)
                assertEquals(listOf(Place(id = "fixture-place", name = "مكان المستخدم")), dao.allPlaces())
                assertEquals(
                    listOf(Errand(
                        id = "fixture-errand",
                        title = "طلب المستخدم",
                        description = "وصف محفوظ",
                        placeId = "fixture-place",
                        quantity = "2",
                        expectedPrice = "100",
                        notes = "ملاحظة محفوظة",
                        createdAt = 123456L
                    )),
                    dao.allErrands()
                )
                assertEquals(listOf(AppSetting("theme", "dark")), dao.allSettings())
        } finally {
            current.close()
        }
    }
}
