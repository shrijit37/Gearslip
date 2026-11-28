package app.seb3thehacker.gearslip.surface

import app.seb3thehacker.gearslip.surface.api.AlertSnapshot
import app.seb3thehacker.gearslip.surface.api.CallSnapshot
import app.seb3thehacker.gearslip.surface.api.CallState
import app.seb3thehacker.gearslip.surface.api.HostedAppSnapshotState
import app.seb3thehacker.gearslip.surface.api.MediaSnapshot
import app.seb3thehacker.gearslip.surface.api.MediaPlaybackState
import app.seb3thehacker.gearslip.surface.api.NavigationTurnSnapshot
import app.seb3thehacker.gearslip.surface.api.NotificationCenterSnapshot
import app.seb3thehacker.gearslip.surface.api.NotificationSnapshot
import app.seb3thehacker.gearslip.surface.api.SensorReadingSnapshot
import app.seb3thehacker.gearslip.surface.api.SessionPhase
import app.seb3thehacker.gearslip.surface.api.SettingsSnapshot
import app.seb3thehacker.gearslip.surface.api.SurfaceLeaseSnapshot
import app.seb3thehacker.gearslip.surface.api.SurfaceSnapshot
import app.seb3thehacker.gearslip.surface.api.ToastSnapshot
import app.seb3thehacker.gearslip.surface.api.VehicleSnapshot
import java.lang.reflect.Modifier
import kotlin.reflect.KClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Guards the Phase 1 promise: the renderer's whole view is made of pure, immutable values. */
class SessionModelsTest {

    @Test fun `a snapshot covers every screen the old car UI had`() {
        val snapshot = SurfaceSnapshot(
            session = app.seb3thehacker.gearslip.surface.api.SessionSnapshot(SessionPhase.PROJECTING),
            media = MediaSnapshot(state = MediaPlaybackState.PLAYING),
            call = CallSnapshot(CallState.ACTIVE),
            notifications = NotificationCenterSnapshot(
                history = listOf(NotificationSnapshot("key", 1, "app", "App", "Title", "Text", 0)),
                unread = 2,
            ),
            navigation = NavigationTurnSnapshot("key", "map", "500 m", "Main St", 1),
            sensors = listOf(SensorReadingSnapshot(3, "Speed (#3)", "varint=5", 10)),
            hostedApps = HostedAppSnapshotState(navigating = true, surfaceEpoch = 4),
            alert = AlertSnapshot(7, "owner", "Alert", listOf("OK")),
            toast = ToastSnapshot("Saved", 4_000, "owner", waiting = 1),
            catalog = listOf(app.seb3thehacker.gearslip.surface.api.CatalogEntrySnapshot("app", "App", "media")),
            lease = SurfaceLeaseSnapshot(granted = true, epoch = 4, generation = 9),
            settings = SettingsSnapshot(night = true),
            carData = VehicleSnapshot(speedMps = 12f),
        )

        assertEquals(SessionPhase.PROJECTING, snapshot.session.phase)
        assertEquals(MediaPlaybackState.PLAYING, snapshot.media.state)
        assertEquals(CallState.ACTIVE, snapshot.call?.state)
        assertEquals(1, snapshot.notifications.history.size)
        assertEquals(2, snapshot.notifications.unread)
        assertEquals("Main St", snapshot.navigation?.road)
        assertEquals(1, snapshot.sensors.size)
        assertTrue(snapshot.hostedApps.navigating)
        assertEquals(4, snapshot.lease.epoch)
        assertEquals(9, snapshot.lease.generation)
        assertTrue(snapshot.settings.night)
        assertEquals("OK", snapshot.alert?.actions?.first())
        assertEquals(1, snapshot.toast?.waiting)
    }

    @Test fun `updating one area leaves the previous snapshot untouched`() {
        val before = SurfaceSnapshot(media = MediaSnapshot(title = "Old"))
        val after = before.copy(media = before.media.copy(title = "New"))

        assertEquals("Old", before.media.title)
        assertEquals("New", after.media.title)
        assertNull(before.alert)
        assertNull(after.alert)
    }

    @Test fun `no snapshot field is an Android or Compose type`() {
        val visited = mutableSetOf<KClass<*>>()
        fun check(type: KClass<*>, path: String) {
            if (!visited.add(type)) return
            val name = type.qualifiedName.orEmpty()
            assertTrue("$path is not a pure Kotlin type: $name", !name.startsWith("android."))
            assertTrue("$path is not a pure Kotlin type: $name", !name.startsWith("androidx."))
            type.java.declaredFields
                .filter { !Modifier.isStatic(it.modifiers) && !it.type.isPrimitive }
                .forEach { field ->
                    val component = if (field.type.isArray) field.type.componentType else field.type
                    if (component.name.startsWith("java.") || component.name.startsWith("kotlin.")) return@forEach
                    check(component.kotlin, "$path.${field.name}")
                }
        }
        check(SurfaceSnapshot::class, "SurfaceSnapshot")
    }

    @Test fun `collections in a snapshot are read-only to consumers`() {
        val snapshot = SurfaceSnapshot(
            sensors = listOf(SensorReadingSnapshot(1, "Speed", "raw", 0)),
            catalog = listOf(app.seb3thehacker.gearslip.surface.api.CatalogEntrySnapshot("pkg", "App", "media")),
        )
        assertEquals(1, snapshot.sensors.size)
        assertEquals(1, snapshot.catalog.size)
    }
}
