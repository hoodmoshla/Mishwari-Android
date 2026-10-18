package ye.mishwari

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.location.Geofence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.awaitAll
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import ye.mishwari.data.Errand
import ye.mishwari.data.Place
import ye.mishwari.data.MishwariDatabase
import ye.mishwari.geo.GeoReceiver
import ye.mishwari.geo.GeoFenceIdentity

/** Tests synthetic ENTER/EXIT delivery, not the Google service's real location detection. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class GeoIntegrationRobolectricTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()
    private val manager: NotificationManager get() = app.getSystemService(NotificationManager::class.java)
    private val dao get() = MishwariDatabase.get(app).dao()
    private val receiver = GeoReceiver()

    @Before fun prepare() = runBlocking {
        manager.cancelAll()
        manager.deleteNotificationChannel("mishwari_geo_alerts")
        app.getSharedPreferences("mishwari_geo_alert_policy", 0).edit().clear().commit()
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        dao.allErrands().forEach { dao.deleteErrand(it.id) }
        dao.allPlaces().forEach { dao.deletePlace(it.id) }
        dao.upsertPlace(Place(id = "market", name = "سوق الاختبار", latitude = 15.0, longitude = 44.0, monitored = true))
        dao.upsertErrand(Errand(id = "one", title = "حاجة أولى", placeId = "market"))
        dao.upsertErrand(Errand(id = "two", title = "حاجة ثانية", placeId = "market", status = "مخطط له"))
        dao.upsertErrand(Errand(id = "done", title = "حاجة منجزة", placeId = "market", status = "مكتمل"))
    }

    @Test fun groupsOnlyPendingTasksNamesPlaceAndDeepLinksThenSuppressesDuplicate() = runBlocking {
        receiver.processTransition(app, Geofence.GEOFENCE_TRANSITION_ENTER, listOf("market", "market"))
        assertEquals(1, manager.activeNotifications.size)
        val notification = manager.activeNotifications.single().notification
        assertTrue(notification.extras.getString(Notification.EXTRA_TITLE)!!.contains("سوق الاختبار"))
        assertTrue(notification.extras.getString(Notification.EXTRA_TEXT)!!.startsWith("2"))
        val body = notification.extras.getCharSequence(Notification.EXTRA_BIG_TEXT).toString()
        assertTrue(body.contains("حاجة أولى")); assertTrue(body.contains("حاجة ثانية")); assertFalse(body.contains("حاجة منجزة"))
        assertEquals("market", shadowOf(notification.contentIntent).savedIntent.getStringExtra("place_id"))
        manager.cancelAll()
        receiver.processTransition(app, Geofence.GEOFENCE_TRANSITION_ENTER, listOf("market"))
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test fun exitRearmsNextVisitAfterCooldownAndCompletionUpdatesCount() = runBlocking {
        receiver.processTransition(app, Geofence.GEOFENCE_TRANSITION_ENTER, listOf("market"), nowMillis = 1_000_000L)
        manager.cancelAll()
        receiver.processTransition(app, Geofence.GEOFENCE_TRANSITION_EXIT, listOf("market"))
        val task = dao.allErrands().single { it.id == "one" }
        dao.upsertErrand(task.copy(status = "مكتمل"))
        receiver.processTransition(app, Geofence.GEOFENCE_TRANSITION_ENTER, listOf("market"), nowMillis = 1_000_000L + 31 * 60 * 1000L)
        assertEquals(1, manager.activeNotifications.size)
        assertTrue(manager.activeNotifications.single().notification.extras.getString(Notification.EXTRA_TEXT)!!.startsWith("1"))
        assertEquals("جديد", task.status)
        assertEquals("مخطط له", dao.allErrands().single { it.id == "two" }.status)
    }

    @Test fun deniedNotificationsDoesNotConsumeReminderAndDisabledPlaceRejectsStaleEvent() = runBlocking {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        receiver.processTransition(app, Geofence.GEOFENCE_TRANSITION_ENTER, listOf("market"))
        assertEquals(0, manager.activeNotifications.size)
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        receiver.processTransition(app, Geofence.GEOFENCE_TRANSITION_ENTER, listOf("market"))
        assertEquals(1, manager.activeNotifications.size)
        manager.cancelAll()
        val market = dao.allPlaces().single()
        dao.upsertPlace(market.copy(monitored = false))
        receiver.processTransition(app, Geofence.GEOFENCE_TRANSITION_ENTER, listOf("market"))
        assertEquals(0, manager.activeNotifications.size)
    }

    @Test fun concurrentDuplicateEntersPostOnlyOneNotification() = runBlocking {
        (0 until 8).map {
            async(Dispatchers.IO) {
                receiver.processTransition(app, Geofence.GEOFENCE_TRANSITION_ENTER, listOf("market"))
            }
        }.awaitAll()

        assertEquals(1, manager.activeNotifications.size)
    }

    @Test fun collidingJavaStringHashesKeepPlaceNotificationsAndDeepLinksDistinct() = runBlocking {
        assertEquals("Aa".hashCode(), "BB".hashCode())
        dao.upsertPlace(Place(id = "Aa", name = "مكان ألف", latitude = 16.0, longitude = 44.0, monitored = true))
        dao.upsertPlace(Place(id = "BB", name = "مكان باء", latitude = 17.0, longitude = 44.0, monitored = true))
        dao.upsertErrand(Errand(id = "aa-task", title = "مهمة ألف", placeId = "Aa"))
        dao.upsertErrand(Errand(id = "bb-task", title = "مهمة باء", placeId = "BB"))

        receiver.processTransition(app, Geofence.GEOFENCE_TRANSITION_ENTER, listOf("Aa", "BB"))

        val active = manager.activeNotifications
        assertEquals(2, active.size)
        assertEquals(2, active.map { it.id }.toSet().size)
        assertEquals(
            setOf("Aa", "BB"),
            active.map { shadowOf(it.notification.contentIntent).savedIntent.getStringExtra("place_id") }.toSet(),
        )
    }

    @Test fun disabledNotificationChannelDoesNotConsumeReminder() = runBlocking {
        manager.createNotificationChannel(
            android.app.NotificationChannel("mishwari_geo_alerts", "تنبيهات الأماكن", NotificationManager.IMPORTANCE_NONE),
        )

        receiver.processTransition(app, Geofence.GEOFENCE_TRANSITION_ENTER, listOf("market"))

        assertTrue(manager.activeNotifications.isEmpty())
        assertFalse(app.getSharedPreferences("mishwari_geo_alert_policy", 0).getBoolean("inside_market", false))
    }

    @Test fun delayedEventFromPreviousGeometryIsRejectedAfterPlaceMoves() = runBlocking {
        val oldRequestId = GeoFenceIdentity.requestId("market", 15.0, 44.0, 500f)
        val market = dao.allPlaces().single()
        dao.upsertPlace(market.copy(latitude = 25.0))

        receiver.processVersionedTransition(app, Geofence.GEOFENCE_TRANSITION_ENTER, listOf(oldRequestId, "market"))
        assertTrue(manager.activeNotifications.isEmpty())
        assertFalse(app.getSharedPreferences("mishwari_geo_alert_policy", 0).getBoolean("inside_market", false))

        val currentRequestId = GeoFenceIdentity.requestId("market", 25.0, 44.0, 500f)
        receiver.processVersionedTransition(app, Geofence.GEOFENCE_TRANSITION_ENTER, listOf(currentRequestId))
        assertEquals(1, manager.activeNotifications.size)
    }

    @Test fun movedPlaceCanAlertAfterCooldownWithoutExitAndAddingPlaceKeepsItsInsideState() = runBlocking {
        val firstEntryAt = 1_000_000L
        val preferences = app.getSharedPreferences("mishwari_geo_alert_policy", 0)
        receiver.processTransition(app, Geofence.GEOFENCE_TRANSITION_ENTER, listOf("market"), nowMillis = firstEntryAt)
        assertTrue(preferences.getBoolean("inside_market", false))
        manager.cancelAll()

        val market = dao.allPlaces().single()
        dao.upsertPlace(market.copy(latitude = 25.0))
        val movedEntryAt = firstEntryAt + 31 * 60 * 1000L
        receiver.processTransition(app, Geofence.GEOFENCE_TRANSITION_ENTER, listOf("market"), nowMillis = movedEntryAt)

        assertEquals(1, manager.activeNotifications.size)
        assertTrue(preferences.getBoolean("inside_market", false))
        assertEquals(movedEntryAt, preferences.getLong("last_alert_market", 0L))

        dao.upsertPlace(Place(id = "pharmacy", name = "صيدلية الاختبار", latitude = 16.0, longitude = 45.0, monitored = true))
        dao.upsertErrand(Errand(id = "pharmacy-task", title = "دواء", placeId = "pharmacy"))
        val pharmacyAlertAt = movedEntryAt + 1_000L
        receiver.processTransition(app, Geofence.GEOFENCE_TRANSITION_ENTER, listOf("pharmacy"), nowMillis = pharmacyAlertAt)

        assertTrue(preferences.getBoolean("inside_market", false))
        assertTrue(preferences.getBoolean("inside_pharmacy", false))
        assertEquals(pharmacyAlertAt, preferences.getLong("last_alert_pharmacy", 0L))

        manager.cancelAll()
        val pharmacy = dao.allPlaces().single { it.id == "pharmacy" }
        dao.upsertPlace(pharmacy.copy(latitude = 17.0))
        receiver.processTransition(app, Geofence.GEOFENCE_TRANSITION_ENTER, listOf("pharmacy"), nowMillis = pharmacyAlertAt + 10 * 60 * 1000L)

        assertTrue(manager.activeNotifications.isEmpty())
        assertEquals(pharmacyAlertAt, preferences.getLong("last_alert_pharmacy", 0L))
        assertTrue(preferences.getBoolean("inside_market", false))
    }
}
