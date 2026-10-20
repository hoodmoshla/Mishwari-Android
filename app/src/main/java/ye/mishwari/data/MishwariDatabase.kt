package ye.mishwari.data

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import org.json.JSONObject

data class MishwariSnapshot(
    val places: List<Place>,
    val errands: List<Errand>,
    val settings: List<AppSetting>
)

@Dao
interface MishwariDao {
    @Query("SELECT * FROM errands ORDER BY createdAt DESC")
    fun observeErrands(): Flow<List<Errand>>

    @Query("SELECT * FROM places ORDER BY name COLLATE NOCASE")
    fun observePlaces(): Flow<List<Place>>

    @Query("SELECT * FROM app_settings ORDER BY `key`")
    fun observeSettings(): Flow<List<AppSetting>>

    @Query("SELECT * FROM errands ORDER BY createdAt DESC")
    suspend fun allErrands(): List<Errand>

    @Transaction
    suspend fun snapshot(): MishwariSnapshot = MishwariSnapshot(
        places = allPlaces(),
        errands = allErrands(),
        settings = allSettings()
    )

    @Query("SELECT * FROM places ORDER BY name COLLATE NOCASE")
    suspend fun allPlaces(): List<Place>

    @Query("SELECT * FROM app_settings ORDER BY `key`")
    suspend fun allSettings(): List<AppSetting>

    @Upsert
    suspend fun upsertErrand(errand: Errand)

    @Query("DELETE FROM errands WHERE id = :id")
    suspend fun deleteErrand(id: String)

    @Upsert
    suspend fun upsertPlace(place: Place)

    @Query("DELETE FROM places WHERE id = :id")
    suspend fun deletePlace(id: String)

    @Upsert
    suspend fun putSetting(setting: AppSetting)

    @Query("SELECT value FROM app_settings WHERE `key` = :key LIMIT 1")
    suspend fun getSetting(key: String): String?

    @Query("SELECT * FROM errands WHERE placeId = :id ORDER BY createdAt DESC")
    suspend fun errandsForPlace(id: String): List<Errand>

    @Upsert
    suspend fun upsertPlaces(places: List<Place>)

    @Upsert
    suspend fun upsertErrands(errands: List<Errand>)

    @Upsert
    suspend fun upsertSettings(settings: List<AppSetting>)

    @Transaction
    suspend fun applySnapshot(places: List<Place>, errands: List<Errand>, settings: List<AppSetting>) {
        upsertPlaces(places)
        upsertErrands(errands)
        upsertSettings(settings)
    }
}

@Database(
    entities = [Place::class, Errand::class, AppSetting::class],
    version = 1,
    exportSchema = true
)
abstract class MishwariDatabase : RoomDatabase() {
    abstract fun dao(): MishwariDao

    companion object {
        private const val DATABASE_NAME = "mishwari.db"
        private const val DIRECTORY_PREFS = "mishwari_bundled_directory"
        private const val DIRECTORY_SEEDED = "seeded_v1"
        private val directorySeedLock = Any()

        @Volatile
        private var instance: MishwariDatabase? = null

        fun get(context: Context): MishwariDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MishwariDatabase::class.java,
                DATABASE_NAME
            ).addCallback(object : RoomDatabase.Callback() {
                override fun onOpen(db: SupportSQLiteDatabase) {
                    seedBundledDirectory(context.applicationContext, db)
                }
            }).build().also { instance = it }
        }

        private fun seedBundledDirectory(context: Context, db: SupportSQLiteDatabase) = synchronized(directorySeedLock) {
            val preferences = context.getSharedPreferences(DIRECTORY_PREFS, Context.MODE_PRIVATE)
            if (preferences.getBoolean(DIRECTORY_SEEDED, false)) return@synchronized

            val root = context.assets.open("yemen-directory.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            val places = root.getJSONArray("places")
            val governors = (0 until places.length()).count { places.getJSONObject(it).optString("kind") == "governorate" }
            val districts = (0 until places.length()).count { places.getJSONObject(it).optString("kind") == "district" }
            val ids = (0 until places.length()).map { places.getJSONObject(it).getString("id") }
            require(places.length() == 358 && governors == 22 && districts == 335 && ids.distinct().size == ids.size) {
                "Bundled directory has unexpected content"
            }
            require(root.optString("source").contains("OCHA Yemen / HDX") && root.optString("source").contains("CC BY-IGO 3.0")) {
                "Bundled directory source or license metadata is missing"
            }

            db.beginTransaction()
            try {
                val ordered = (0 until places.length()).map { places.getJSONObject(it) }
                    .sortedBy { when (it.optString("kind")) { "country" -> 0; "governorate" -> 1; else -> 2 } }
                ordered.forEach { place ->
                    val values = ContentValues().apply {
                        put("id", place.getString("id"))
                        put("name", place.getString("name"))
                        if (place.has("parentId")) put("parentId", place.getString("parentId")) else putNull("parentId")
                        put("kind", place.optString("kind", "custom"))
                        put("governorate", place.optString("governorate"))
                        put("district", place.optString("district"))
                        put("area", place.optString("area"))
                        put("detail", place.optString("detail"))
                        putNull("latitude")
                        putNull("longitude")
                        put("radiusMeters", 500f)
                        put("monitored", 0)
                        put("source", place.optString("source", root.optString("source")))
                        put("sourceUpdated", place.optString("sourceUpdated", root.optString("updated")))
                    }
                    db.insert("places", SQLiteDatabase.CONFLICT_IGNORE, values)
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
            preferences.edit().putBoolean(DIRECTORY_SEEDED, true).apply()
        }
    }
}
