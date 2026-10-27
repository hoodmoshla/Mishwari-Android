package ye.mishwari.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

@Entity(
    tableName = "errands",
    foreignKeys = [
        ForeignKey(
            entity = Place::class,
            parentColumns = ["id"],
            childColumns = ["placeId"],
            onDelete = ForeignKey.SET_NULL,
            onUpdate = ForeignKey.CASCADE
        )
    ],
    indices = [Index("placeId")]
)
data class Errand(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val title: String,
    val description: String = "",
    val placeId: String? = null,
    val category: String = "مشتريات",
    val quantity: String = "",
    val expectedPrice: String = "",
    @ColumnInfo(defaultValue = "''") val actualPrice: String = "",
    val notes: String = "",
    val priority: Int = 1,
    val dueDate: String = "",
    val photoPath: String? = null,
    val status: String = "جديد",
    val createdAt: Long = System.currentTimeMillis()
)
