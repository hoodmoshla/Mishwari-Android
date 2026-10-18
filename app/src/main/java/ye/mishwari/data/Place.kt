package ye.mishwari.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "places",
    foreignKeys = [
        ForeignKey(
            entity = Place::class,
            parentColumns = ["id"],
            childColumns = ["parentId"],
            onDelete = ForeignKey.RESTRICT,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [Index("parentId")]
)
data class Place(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val parentId: String? = null,
    val kind: String = "custom",
    val governorate: String = "",
    val district: String = "",
    val area: String = "",
    val detail: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val radiusMeters: Float = 500f,
    val monitored: Boolean = false,
    val source: String = "يدوي",
    val sourceUpdated: String = ""
)
