package ye.mishwari.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

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

        @Volatile
        private var instance: MishwariDatabase? = null

        fun get(context: Context): MishwariDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MishwariDatabase::class.java,
                DATABASE_NAME
            ).build().also { instance = it }
        }
    }
}
