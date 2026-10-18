package ye.mishwari.geo

import ye.mishwari.data.Errand
import ye.mishwari.data.Place

/** Pure selection rules shared by the scheduler and JVM tests. */
object GeoPolicy {
    const val MAX_GEOFENCES = 90
    private val activeStatuses = setOf("new", "planned", "deferred", "جديد", "مخطط له", "مؤجل")

    data class Candidate(
        val id: String,
        val parentId: String?,
        val latitude: Double?,
        val longitude: Double?,
        val radiusMeters: Float = 500f,
        val monitored: Boolean = false,
        val activeTaskCount: Int = 0,
        val priorityRank: Int = 0,
    )

    /** Build normalized selection inputs from the agreed Room models. */
    fun fromData(places: List<Place>, errands: List<Errand>, travelPlaceId: String?): List<Candidate> {
        val active = errands.filter { isActiveStatus(it.status) && !it.placeId.isNullOrBlank() }
            .groupBy { it.placeId!! }
        return places.map { place ->
            val tasks = active[place.id].orEmpty()
            Candidate(
                id = place.id,
                parentId = place.parentId,
                latitude = place.latitude,
                longitude = place.longitude,
                radiusMeters = place.radiusMeters,
                monitored = place.monitored,
                activeTaskCount = tasks.size,
                priorityRank = tasks.maxOfOrNull { it.priority } ?: 0,
            )
        }
    }

    /**
     * With a travel place selected, only that place and its descendants qualify;
     * otherwise only explicitly monitored places qualify. Places without active
     * tasks or valid coordinates are always excluded.
     */
    fun select(candidates: List<Candidate>, travelPlaceId: String?, limit: Int = MAX_GEOFENCES): List<Candidate> {
        val byId = candidates.associateBy { it.id }
        val ceiling = limit.coerceIn(0, MAX_GEOFENCES)
        return candidates.asSequence()
            .filter { it.activeTaskCount > 0 }
            .filter { validCoordinates(it.latitude, it.longitude) }
            .filter { it.radiusMeters.isFinite() && it.radiusMeters > 0f }
            .filter { candidate ->
                if (travelPlaceId == null) candidate.monitored
                else belongsTo(candidate, travelPlaceId, byId)
            }
            .distinctBy { it.id }
            .sortedWith(compareByDescending<Candidate> { it.priorityRank }
                .thenByDescending { it.activeTaskCount }
                .thenBy { it.id })
            .take(ceiling)
            .toList()
    }

    fun isDescendantOrSelf(placeId: String, ancestorId: String, candidates: List<Candidate>): Boolean {
        val place = candidates.firstOrNull { it.id == placeId } ?: return false
        return belongsTo(place, ancestorId, candidates.associateBy { it.id })
    }

    private fun belongsTo(candidate: Candidate, rootId: String, byId: Map<String, Candidate>): Boolean {
        var current: Candidate? = candidate
        val visited = HashSet<String>()
        while (current != null && visited.add(current.id)) {
            if (current.id == rootId) return true
            current = current.parentId?.let(byId::get)
        }
        return false
    }

    private fun validCoordinates(latitude: Double?, longitude: Double?): Boolean =
        latitude != null && longitude != null && latitude.isFinite() && longitude.isFinite() &&
            latitude in -90.0..90.0 && longitude in -180.0..180.0

    fun isActiveStatus(status: String?): Boolean = status?.trim() in activeStatuses
}
