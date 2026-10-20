package ye.mishwari

import ye.mishwari.data.MishwariDao

/** Removes test places without violating the directory's parent-child RESTRICT foreign key. */
suspend fun clearPlacesChildrenFirst(dao: MishwariDao) {
    // Preserve the bundled country/governorate/district directory; remove only test-created custom places.
    val remaining = dao.allPlaces().filter { it.kind == "custom" }.associateByTo(linkedMapOf()) { it.id }
    while (remaining.isNotEmpty()) {
        val parentIds = remaining.values.mapNotNull { it.parentId }.toSet()
        val leaves = remaining.values.filter { it.id !in parentIds }
        check(leaves.isNotEmpty()) { "Place hierarchy contains a parent cycle" }
        leaves.forEach { place ->
            dao.deletePlace(place.id)
            remaining.remove(place.id)
        }
    }
}
