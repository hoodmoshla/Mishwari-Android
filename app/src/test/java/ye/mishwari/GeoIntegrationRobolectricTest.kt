package ye.mishwari

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import androidx.test.core.app.ApplicationProvider
import com.google.android.gms.location.Geofence
import kotlinx.coroutines.runBlocking
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
}
