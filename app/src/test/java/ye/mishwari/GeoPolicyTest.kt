package ye.mishwari

import org.junit.Assert.*
import org.junit.Test
import ye.mishwari.geo.AlertPolicy
import ye.mishwari.geo.GeoPolicy

class GeoPolicyTest {
    private fun place(
        id: String,
        parent: String? = null,
        lat: Double? = 24.7,
        lon: Double? = 46.7,
        monitored: Boolean = false,
        tasks: Int = 1,
        priority: Int = 0,
    ) = GeoPolicy.Candidate(id, parent, lat, lon, monitored = monitored, activeTaskCount = tasks, priorityRank = priority)

    @Test fun monitoredOnlyWhenNoTripAndRequireActiveTaskAndCoordinates() {
        val input = listOf(
            place("monitored", monitored = true),
            place("not-monitored"),
            place("empty", monitored = true, tasks = 0),
            place("no-coordinate", monitored = true, lat = null),
        )
        assertEquals(listOf("monitored"), GeoPolicy.select(input, travelPlaceId = null).map { it.id })
    }

    @Test fun travelPlaceIncludesDescendantsButNotUnselectedFences() {
        val input = listOf(
            place("trip"), place("child", "trip"), place("grandchild", "child"),
            place("other", monitored = true),
        )
        assertEquals(listOf("child", "grandchild", "trip"), GeoPolicy.select(input, "trip").map { it.id }.sorted())
        assertTrue(GeoPolicy.isDescendantOrSelf("grandchild", "trip", input))
        assertFalse(GeoPolicy.isDescendantOrSelf("other", "trip", input))
        assertTrue(GeoPolicy.select(input, "missing").isEmpty())
    }

    @Test fun priorityThenTaskCountAndHardCapAreApplied() {
        val input = (0 until 100).map { index ->
            place("p%03d".format(index), tasks = if (index % 2 == 0) 1 else 2, priority = if (index == 99) 9 else index % 4, monitored = true)
        }
        val selected = GeoPolicy.select(input, null, limit = 200)
        assertEquals(GeoPolicy.MAX_GEOFENCES, selected.size)
        assertEquals("p099", selected.first().id)
        assertEquals(listOf("p099", "p003", "p007"), selected.take(3).map { it.id })
    }

    @Test fun hierarchyTraversalHandlesCyclesAndRadiusOrCoordinatesMustBeValid() {
        val input = listOf(
            place("root"), place("a", "b"), place("b", "a"),
            place("bad-lat", "root", lat = 91.0), place("bad-radius", "root").copy(radiusMeters = Float.NaN),
        )
        assertFalse(GeoPolicy.isDescendantOrSelf("a", "root", input))
        assertEquals(listOf("root"), GeoPolicy.select(input, "root").map { it.id })
    }

    @Test fun activeStatusesAcceptStoredArabicAndAgreedEnglishFormsOnly() {
        listOf("جديد", "مخطط له", "مؤجل", "new", "planned", "deferred").forEach {
            assertTrue("$it should count as active", GeoPolicy.isActiveStatus(it))
        }
        listOf("مكتمل", "ملغى", "completed", "cancelled", null).forEach {
            assertFalse("$it should not count as active", GeoPolicy.isActiveStatus(it))
        }
    }

    @Test fun enterExitCooldownAndMissingPermissionsBehaveSafely() {
        val now = 1_000_000L
        val empty = AlertPolicy.State()
        val noPermission = AlertPolicy.onEnter(empty, now, hasActiveTasks = true, notificationsAllowed = false)
        assertFalse(noPermission.shouldNotify)
        assertFalse(noPermission.shouldPersist)
        val noTasks = AlertPolicy.onEnter(empty, now, hasActiveTasks = false, notificationsAllowed = true)
        assertFalse(noTasks.shouldPersist)

        val first = AlertPolicy.onEnter(empty, now, hasActiveTasks = true, notificationsAllowed = true)
        assertTrue(first.shouldNotify)
        assertTrue(first.state.inside)
        assertFalse(AlertPolicy.onEnter(first.state, now + 1, true, true).shouldNotify)

        val exit = AlertPolicy.onExit(first.state)
        assertFalse(exit.state.inside)
        val tooSoon = AlertPolicy.onEnter(exit.state, now + AlertPolicy.MIN_COOLDOWN_MILLIS - 1, true, true)
        assertFalse(tooSoon.shouldNotify)
        assertTrue(tooSoon.state.inside)
        val rearmed = AlertPolicy.onExit(tooSoon.state)
        val afterCooldown = AlertPolicy.onEnter(rearmed.state, now + AlertPolicy.MIN_COOLDOWN_MILLIS, true, true)
        assertTrue(afterCooldown.shouldNotify)
    }
}
