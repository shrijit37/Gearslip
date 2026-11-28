package app.seb3thehacker.gearslip.surface

import app.seb3thehacker.gearslip.surface.api.AlertSnapshot
import app.seb3thehacker.gearslip.surface.api.CallSnapshot
import app.seb3thehacker.gearslip.surface.api.CallState
import app.seb3thehacker.gearslip.surface.api.CallerSnapshot
import app.seb3thehacker.gearslip.surface.api.FrameGeometry
import app.seb3thehacker.gearslip.surface.api.NotificationSnapshot
import app.seb3thehacker.gearslip.surface.api.HostedAppSnapshotState
import app.seb3thehacker.gearslip.surface.api.MediaPlaybackState
import app.seb3thehacker.gearslip.surface.api.MediaSnapshot
import app.seb3thehacker.gearslip.surface.api.NavigationTurnSnapshot
import app.seb3thehacker.gearslip.surface.api.NotificationCenterSnapshot
import app.seb3thehacker.gearslip.surface.api.ScreenInsets
import app.seb3thehacker.gearslip.surface.api.SensorReadingSnapshot
import app.seb3thehacker.gearslip.surface.api.SessionPhase
import app.seb3thehacker.gearslip.surface.api.SessionSnapshot
import app.seb3thehacker.gearslip.surface.api.SettingsSnapshot
import app.seb3thehacker.gearslip.surface.api.SurfaceLeaseSnapshot
import app.seb3thehacker.gearslip.surface.api.ToastSnapshot
import app.seb3thehacker.gearslip.surface.api.VehicleLocation
import app.seb3thehacker.gearslip.surface.api.VehicleModel
import app.seb3thehacker.gearslip.surface.api.VehicleSnapshot
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fake sources let the session boundary be tested on the JVM without Android stubs. */
private class FakeSources : SessionSources {
    val sessionFlow = MutableStateFlow(SessionSnapshot(SessionPhase.IDLE))
    val frameFlow = MutableStateFlow(FrameGeometry(800, 480, 30, 160, false))
    val insetsFlow = MutableStateFlow(ScreenInsets.NONE)
    val nightFlow = MutableStateFlow(false)
    val vehicleFlow = MutableStateFlow("")
    val modelFlow = MutableStateFlow<VehicleModel?>(null)
    val dataFlow = MutableStateFlow(VehicleSnapshot())
    val mediaFlow = MutableStateFlow(MediaSnapshot())
    val callFlow = MutableStateFlow<CallSnapshot?>(null)
    val notificationsFlow = MutableStateFlow(NotificationCenterSnapshot())
    val navigationFlow = MutableStateFlow<NavigationTurnSnapshot?>(null)
    val sensorsFlow = MutableStateFlow<List<SensorReadingSnapshot>>(emptyList())
    val hostedAppsFlow = MutableStateFlow(HostedAppSnapshotState())
    val alertFlow = MutableStateFlow<AlertSnapshot?>(null)
    val toastFlow = MutableStateFlow<ToastSnapshot?>(null)
    val catalogFlow = MutableStateFlow(emptyList<app.seb3thehacker.gearslip.surface.api.CatalogEntrySnapshot>())
    val leaseFlow = MutableStateFlow(SurfaceLeaseSnapshot())
    val settingsFlow = MutableStateFlow(SettingsSnapshot())

    override val session: StateFlow<SessionSnapshot> get() = sessionFlow
    override val frame: StateFlow<FrameGeometry> get() = frameFlow
    override val insets: StateFlow<ScreenInsets> get() = insetsFlow
    override val night: StateFlow<Boolean> get() = nightFlow
    override val vehicle: StateFlow<String> get() = vehicleFlow
    override val vehicleModel: StateFlow<VehicleModel?> get() = modelFlow
    override val carData: StateFlow<VehicleSnapshot> get() = dataFlow
    override val media: StateFlow<MediaSnapshot> get() = mediaFlow
    override val call: StateFlow<CallSnapshot?> get() = callFlow
    override val notifications: StateFlow<NotificationCenterSnapshot> get() = notificationsFlow
    override val navigation: StateFlow<NavigationTurnSnapshot?> get() = navigationFlow
    override val sensors: StateFlow<List<SensorReadingSnapshot>> get() = sensorsFlow
    override val hostedApps: StateFlow<HostedAppSnapshotState> get() = hostedAppsFlow
    override val alert: StateFlow<AlertSnapshot?> get() = alertFlow
    override val toast: StateFlow<ToastSnapshot?> get() = toastFlow
    override val catalog: StateFlow<List<app.seb3thehacker.gearslip.surface.api.CatalogEntrySnapshot>> get() = catalogFlow
    override val lease: StateFlow<SurfaceLeaseSnapshot> get() = leaseFlow
    override val settings: StateFlow<SettingsSnapshot> get() = settingsFlow
}

class SessionStateTest {

    @Test fun `a session starts with its generation and mirrors every source area`() = runBlocking {
        val sources = FakeSources()
        val state = SessionState(42, sources)
        try {
            state.start()
            delay(100) // let the collectors take the initial values

            val before = state.snapshot.value
            assertEquals(42, before.lease.generation)
            assertEquals(SessionPhase.IDLE, before.session.phase)

            state.setVideoSpec(VideoSpec(1024, 600, 60, 200, true))
            sources.sessionFlow.value = SessionSnapshot(SessionPhase.PROJECTING)
            sources.mediaFlow.value = MediaSnapshot(state = MediaPlaybackState.PLAYING)
            sources.callFlow.value = CallSnapshot(CallState.ACTIVE)
            sources.navigationFlow.value = NavigationTurnSnapshot("k", "map", "500 m", "Main St", 1)
            sources.sensorsFlow.value = listOf(SensorReadingSnapshot(3, "Speed", "raw", 9))
            sources.alertFlow.value = AlertSnapshot(1, "owner", "Alert")
            sources.toastFlow.value = ToastSnapshot("Saved", 4_000, "owner")
            sources.leaseFlow.value = SurfaceLeaseSnapshot(granted = true, epoch = 2)
            sources.settingsFlow.value = SettingsSnapshot(night = true)
            delay(100)

            val after = state.snapshot.value
            assertEquals(1024, after.frame.width)
            assertEquals(60, after.frame.frameRate)
            assertEquals(200, after.frame.densityDpi)
            assertTrue(after.frame.wireless)
            assertEquals(SessionPhase.PROJECTING, after.session.phase)
            assertEquals(MediaPlaybackState.PLAYING, after.media.state)
            assertEquals(CallState.ACTIVE, after.call?.state)
            assertEquals("Main St", after.navigation?.road)
            assertEquals(1, after.sensors.size)
            assertEquals("Alert", after.alert?.title)
            assertEquals("Saved", after.toast?.text)
            assertTrue(after.lease.granted)
            assertEquals(2, after.lease.epoch)
            assertEquals(42, after.lease.generation) // generation is owned by the session
            assertTrue(after.settings.night)
            assertNotSame(before, after)
            assertEquals(SessionPhase.IDLE, before.session.phase) // earlier snapshot untouched
        } finally {
            state.stop()
        }
    }

    @Test fun `two sessions do not share state or generations`() = runBlocking {
        val firstSources = FakeSources()
        val secondSources = FakeSources()
        val first = SessionState(1, firstSources)
        val second = SessionState(2, secondSources)
        try {
            first.start()
            second.start()
            delay(100)

            firstSources.sessionFlow.value = SessionSnapshot(SessionPhase.FAILED)
            firstSources.mediaFlow.value = MediaSnapshot(title = "Only the first")
            delay(100)

            assertEquals(SessionPhase.FAILED, first.snapshot.value.session.phase)
            assertEquals("Only the first", first.snapshot.value.media.title)
            assertEquals(SessionPhase.IDLE, second.snapshot.value.session.phase)
            assertEquals("", second.snapshot.value.media.title)
            assertEquals(1, first.snapshot.value.lease.generation)
            assertEquals(2, second.snapshot.value.lease.generation)
        } finally {
            first.stop()
            second.stop()
        }
    }

    @Test fun `a stopped session cannot publish a later source change`() = runBlocking {
        val sources = FakeSources()
        val state = SessionState(7, sources)
        state.start()
        delay(100)
        state.stop()
        val frozen = state.snapshot.value

        sources.sessionFlow.value = SessionSnapshot(SessionPhase.PROJECTING)
        sources.callFlow.value = CallSnapshot(CallState.INCOMING)
        delay(100)

        assertEquals(frozen, state.snapshot.value)
        assertNull(state.snapshot.value.call)
    }
    @Test fun `an untrusted session gets caller, message and location details removed`() = runBlocking {
        val sources = FakeSources()
        sources.callFlow.value = CallSnapshot(CallState.INCOMING, CallerSnapshot("Alice", "+1 555"))
        sources.notificationsFlow.value = NotificationCenterSnapshot(
            history = listOf(
                NotificationSnapshot(
                    key = "k", id = 1, packageName = "chat", appLabel = "Chat",
                    title = "Alice", text = "private message", postedAt = 0,
                ),
            ),
        )
        sources.dataFlow.value = VehicleSnapshot(location = VehicleLocation(1.0, 2.0, 3f, 4.0, 5f, 6f))
        sources.mediaFlow.value = MediaSnapshot(title = "Private track")
        sources.navigationFlow.value = NavigationTurnSnapshot("k", "map", "500 m", "Home", 1)
        val state = SessionState(9, sources, trusted = false)
        try {
            state.start()
            delay(100)
            val view = state.snapshot.value
            assertEquals(CallState.INCOMING, view.call?.state)
            assertEquals("", view.call?.caller?.name)
            assertEquals("", view.call?.caller?.number)
            assertEquals("", view.notifications.history.first().title)
            assertEquals("", view.notifications.history.first().text)
            assertTrue(view.notifications.history.first().lines.isEmpty())
            assertNull(view.carData.location)
            assertEquals("", view.media.title)
            assertEquals("", view.navigation?.road)
        } finally {
            state.stop()
        }
    }

}
