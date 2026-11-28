package app.seb3thehacker.gearslip.surface

import android.media.session.PlaybackState
import android.os.SystemClock
import app.seb3thehacker.gearslip.Insets
import app.seb3thehacker.gearslip.SessionStatus
import app.seb3thehacker.gearslip.call.CallUiState
import app.seb3thehacker.gearslip.call.CarCalls
import app.seb3thehacker.gearslip.car.CarAlerts
import app.seb3thehacker.gearslip.car.CarData
import app.seb3thehacker.gearslip.car.CarEnvironment
import app.seb3thehacker.gearslip.car.CarSensors
import app.seb3thehacker.gearslip.car.CarServices
import app.seb3thehacker.gearslip.car.CarSettings
import app.seb3thehacker.gearslip.car.NightMode
import app.seb3thehacker.gearslip.car.CarToasts
import app.seb3thehacker.gearslip.host.CustomCarApps
import app.seb3thehacker.gearslip.host.text
import app.seb3thehacker.gearslip.media.NowPlaying
import app.seb3thehacker.gearslip.notify.CarNotification
import app.seb3thehacker.gearslip.notify.CarNotifications
import app.seb3thehacker.gearslip.notify.PhoneNavigation
import app.seb3thehacker.gearslip.surface.api.AlertSnapshot
import app.seb3thehacker.gearslip.surface.api.CallSnapshot
import app.seb3thehacker.gearslip.surface.api.CallState
import app.seb3thehacker.gearslip.surface.api.CallerSnapshot
import app.seb3thehacker.gearslip.surface.api.CatalogEntrySnapshot
import app.seb3thehacker.gearslip.surface.api.ChatLineSnapshot
import app.seb3thehacker.gearslip.surface.api.FrameGeometry
import app.seb3thehacker.gearslip.surface.api.HostedAppSnapshot
import app.seb3thehacker.gearslip.surface.api.HostedAppSnapshotState
import app.seb3thehacker.gearslip.surface.api.LinkType
import app.seb3thehacker.gearslip.surface.api.MediaPlaybackState
import app.seb3thehacker.gearslip.surface.api.MediaSnapshot
import app.seb3thehacker.gearslip.surface.api.NavigationTurnSnapshot
import app.seb3thehacker.gearslip.surface.api.NotificationCenterSnapshot
import app.seb3thehacker.gearslip.surface.api.NotificationSnapshot
import app.seb3thehacker.gearslip.surface.api.ScreenInsets
import app.seb3thehacker.gearslip.surface.api.SensorReadingSnapshot
import app.seb3thehacker.gearslip.surface.api.SessionPhase
import app.seb3thehacker.gearslip.surface.api.SessionSnapshot
import app.seb3thehacker.gearslip.surface.api.SettingsSnapshot
import app.seb3thehacker.gearslip.surface.api.SurfaceLeaseSnapshot
import app.seb3thehacker.gearslip.surface.api.TemplateSnapshot
import app.seb3thehacker.gearslip.surface.api.ToastSnapshot
import app.seb3thehacker.gearslip.surface.api.TrackSnapshot
import app.seb3thehacker.gearslip.surface.api.VehicleLocation
import app.seb3thehacker.gearslip.surface.api.VehicleModel
import app.seb3thehacker.gearslip.surface.api.VehicleSnapshot
import app.seb3thehacker.gearslip.surface.StateFlows.startingWith
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.map
import java.util.UUID
import java.util.WeakHashMap
import androidx.car.app.model.Template
import app.seb3thehacker.gearslip.host.CarAppConnection

/**
 * Pure, API-typed inputs for one session's snapshot.
 *
 * Keeping the boundary at this interface means [SessionState] can be tested with fake sources on
 * the JVM, while [GlobalSources] is the only place that knows how to read Android-flavoured core
 * objects. No renderer ever sees those objects.
 */
interface SessionSources {
    val session: StateFlow<SessionSnapshot>
    val frame: StateFlow<FrameGeometry>
    val insets: StateFlow<ScreenInsets>
    val night: StateFlow<Boolean>
    val vehicle: StateFlow<String>
    val vehicleModel: StateFlow<VehicleModel?>
    val carData: StateFlow<VehicleSnapshot>
    val media: StateFlow<MediaSnapshot>
    val call: StateFlow<CallSnapshot?>
    val notifications: StateFlow<NotificationCenterSnapshot>
    val navigation: StateFlow<NavigationTurnSnapshot?>
    val sensors: StateFlow<List<SensorReadingSnapshot>>
    val hostedApps: StateFlow<HostedAppSnapshotState>
    val alert: StateFlow<AlertSnapshot?>
    val toast: StateFlow<ToastSnapshot?>
    val catalog: StateFlow<List<CatalogEntrySnapshot>>
    val lease: StateFlow<SurfaceLeaseSnapshot>
    val settings: StateFlow<SettingsSnapshot>
}

/** The real session: reads the process-wide owners and projects them into immutable values. */
object GlobalSources : SessionSources {

    override val session = SessionStatus.state.map { snapshot ->
        SessionSnapshot(
            phase = SessionPhase.valueOf(snapshot.phase.name),
            stuckOn = snapshot.stuckOn?.let { link -> LinkType.valueOf(link.name) },
        )
    }.asState(SessionSnapshot(SessionPhase.IDLE))

    override val frame = CarEnvironment.frame.map { f ->
        FrameGeometry(f.width, f.height, 0, f.densityDpi, false)
    }.asState(FrameGeometry(800, 480, 0, 160, false))

    override val insets = CarEnvironment.insets.map { i ->
        ScreenInsets(i.top, i.bottom, i.left, i.right)
    }.asState(ScreenInsets.NONE)

    override val night = CarEnvironment.darkOutside.asState(false)
    override val vehicle = CarEnvironment.vehicle.asState("")

    override val vehicleModel = CarEnvironment.vehicle.map { name ->
        name.takeIf(String::isNotBlank)?.let { VehicleModel(it, null) }
    }.asState(null)

    override val carData = CarData.state.map { data ->
        VehicleSnapshot(
            speedMps = data.speedMps,
            odometerKm = data.odometerKm,
            fuelPercent = data.fuelPercent,
            rangeKm = data.rangeKm,
            fuelLow = data.fuelLow,
            compassDegrees = data.compassDegrees,
            location = data.location?.let { loc ->
                VehicleLocation(
                    loc.latitude, loc.longitude, loc.accuracyMeters, loc.altitudeMeters,
                    loc.speedMps, loc.bearingDegrees,
                )
            },
        )
    }.asState(VehicleSnapshot())

    override val media = combine(
        CarServices.media.now,
        CarServices.media.modes,
        CarServices.media.queue,
        CarServices.mediaApp,
    ) { now, modes, queue, app ->
        MediaSnapshot(
            source = app?.label.orEmpty(),
            title = now.title,
            artist = now.artist,
            album = now.album,
            artToken = now.artUri?.toString().orEmpty(),
            state = mediaState(now),
            positionMs = now.positionMs,
            durationMs = now.durationMs,
            shuffle = modes.shuffle != 0,
            repeat = modes.repeat,
            error = now.error,
            queue = queue.map { row ->
                TrackSnapshot(
                    id = row.queueId.toString(),
                    title = row.title,
                    artist = row.subtitle,
                    artToken = row.iconUri?.toString().orEmpty(),
                    durationSeconds = 0,
                )
            },
            activeIndex = -1,
        )
    }.asState(MediaSnapshot())

    /** A 1 Hz tick so a call's displayed duration advances without a state change. */
    private val callTick = flow {
        while (true) {
            emit(Unit)
            delay(1_000)
        }
    }

    override val call = combine(CarCalls.state, CarCalls.muted, callTick) { state, muted, _ ->
        when (state) {
            CallUiState.None -> null
            is CallUiState.Ringing -> CallSnapshot(CallState.INCOMING, caller(state.caller))
            is CallUiState.Active -> CallSnapshot(
                if (muted) CallState.MUTED else CallState.ACTIVE,
                state.caller?.let(::caller) ?: CallerSnapshot(),
                // CarCalls stamps `since` with uptimeMillis; wall-clock subtraction would be
                // off by the epoch and show a nonsensical duration from the first frame.
                ((SystemClock.uptimeMillis() - state.since).coerceAtLeast(0) / 1000),
            )
        }
    }.asState(null)

    override val notifications = combine(
        CarNotifications.history,
        CarNotifications.popup,
        CarNotifications.unread,
        CarNotifications.listening,
    ) { history, popup, unread, listening ->
        NotificationCenterSnapshot(
            history = history.map(::notification),
            popup = popup?.let(::notification),
            unread = unread,
            listening = listening,
        )
    }.asState(NotificationCenterSnapshot())

    override val navigation = PhoneNavigation.turn.map { turn ->
        turn?.let {
            NavigationTurnSnapshot(it.key, it.packageName, it.distanceText, it.road, it.maneuver)
        }
    }.asState(null)

    override val sensors = CarSensors.readings.map { rows ->
        rows.map { r -> SensorReadingSnapshot(r.typeId, r.typeName, r.raw, r.updatedAtMs) }
    }.asState(emptyList())

    private data class HostState(
        val status: CarAppConnection.Status,
        val template: Template?,
        val panMode: Boolean,
        val atRoot: Boolean,
        val surfaceEpoch: Int,
    )

    override val hostedApps = combine(
        CarServices.nav.status,
        CarServices.nav.template,
        CarServices.nav.panMode,
        CarServices.nav.atRoot,
        CarServices.nav.surfaceEpoch,
    ) { status, template, panMode, atRoot, epoch ->
        HostState(status, template, panMode, atRoot, epoch)
    }.combine(CustomCarApps.state) { host, apps ->
        HostedAppSnapshotState(
            apps = apps.orEmpty().map { app ->
                HostedAppSnapshot(
                    packageName = app.packageName,
                    label = app.packageName.substringAfterLast('.'),
                    category = app.kind.id,
                )
            },
            template = TemplateSnapshot(
                appPackage = host.status.app.orEmpty(),
                title = host.status.template.orEmpty().ifBlank { host.template?.javaClass?.simpleName.orEmpty() },
            ),
            navigating = host.status.phase == CarAppConnection.Phase.RUNNING,
            panMode = host.panMode,
            atRoot = host.atRoot,
            surfaceEpoch = host.surfaceEpoch,
        )
    }.asState(HostedAppSnapshotState())

    override val alert = CarAlerts.shown.map { shown ->
        shown?.let {
            AlertSnapshot(
                id = it.alert.id,
                ownerToken = ownerToken(it.owner),
                title = it.alert.title.text(),
                actions = it.alert.actions.map { action -> action.title.text() },
                durationMs = it.alert.durationMillis,
            )
        }
    }.asState(null)

    override val toast = combine(CarToasts.message, CarToasts.waiting) { message, waiting ->
        message?.let {
            ToastSnapshot(it.text, it.durationMs, ownerToken(it.owner), waiting)
        }
    }.asState(null)

    override val catalog = CustomCarApps.state.map { apps ->
        apps.orEmpty().map { app ->
            CatalogEntrySnapshot(app.packageName, app.packageName.substringAfterLast('.'), app.kind.id)
        }
    }.asState(emptyList())

    override val lease = combine(CarServices.nav.status, CarServices.nav.surfaceEpoch) { status, epoch ->
        SurfaceLeaseSnapshot(granted = status.surfaceAttached, epoch = epoch)
    }.asState(SurfaceLeaseSnapshot())

    override val settings = combine(
        CarSettings.nightMode,
        CarSettings.scale,
        CarSettings.adaptiveVideoEnabled,
        CarSettings.pipeAudio,
    ) { mode, scale, adaptiveVideo, pipeAudio ->
        SettingsSnapshot(
            night = mode == NightMode.NIGHT,
            scale = scale,
            adaptiveVideo = adaptiveVideo,
            pipeAudio = pipeAudio,
        )
    }.asState(SettingsSnapshot())

    /** Identity-based tokens: random, stable for an owner's lifetime, never recycled by the GC. */
    private class IdentityKey(val value: Any)
    private val ownerTokens = WeakHashMap<IdentityKey, String>()

    private fun ownerToken(owner: Any): String = synchronized(ownerTokens) {
        ownerTokens.getOrPut(IdentityKey(owner)) { UUID.randomUUID().toString() }
    }

    private fun mediaState(now: NowPlaying) = when (now.state) {
        PlaybackState.STATE_PLAYING -> MediaPlaybackState.PLAYING
        PlaybackState.STATE_PAUSED -> MediaPlaybackState.PAUSED
        PlaybackState.STATE_BUFFERING -> MediaPlaybackState.BUFFERING
        else -> MediaPlaybackState.STOPPED
    }

    private fun caller(value: app.seb3thehacker.gearslip.call.Caller) =
        CallerSnapshot(value.name.orEmpty(), value.number)

    private fun notification(value: CarNotification) = NotificationSnapshot(
        key = value.key,
        id = value.id,
        packageName = value.packageName,
        appLabel = value.appLabel,
        title = value.title,
        text = value.text,
        postedAt = value.postedAt,
        canReply = value.reply != null,
        replied = value.replied,
        lines = value.lines.map { line -> ChatLineSnapshot(line.sender, line.text, line.at) },
        isGroup = value.isGroup,
        read = value.read,
        burst = value.burst,
        shownAt = value.shownAt,
    )

    /** A StateFlow with a JVM-safe initial value, used while the real owners are still loading. */
    private fun <T> kotlinx.coroutines.flow.Flow<T>.asState(initial: T): StateFlow<T> =
        with(StateFlows) { startingWith(initial) }
}
