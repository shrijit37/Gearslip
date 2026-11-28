package app.seb3thehacker.gearslip.surface.api

/**
 * Pure session snapshots for every screen the head unit can show.
 *
 * Nothing here holds an Android object: artwork is a URI/token a renderer can resolve, key codes
 * are plain integers, and car-app templates are described by these value types rather than by
 * `androidx.car.app` classes. A renderer therefore sees state without owning it, and a JVM test can
 * build every snapshot without stubs. The core keeps timers, callbacks and transport rules.
 */

/** Playback state of the external media app that is currently connected. */
enum class MediaPlaybackState { STOPPED, PLAYING, PAUSED, BUFFERING }

/** What a renderer needs to draw the media screen. */
data class MediaSnapshot(
    val source: String = "",
    val title: String = "",
    val artist: String = "",
    val album: String = "",
    /** A token the renderer resolves to pixels; never a Bitmap. Empty when there is no artwork. */
    val artToken: String = "",
    val state: MediaPlaybackState = MediaPlaybackState.STOPPED,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val shuffle: Boolean = false,
    /** 0 = off, 1 = repeat all, 2 = repeat one. */
    val repeat: Int = 0,
    val error: String? = null,
    val queue: List<TrackSnapshot> = emptyList(),
    val activeIndex: Int = -1,
)

/** One playable row of the media queue. */
data class TrackSnapshot(
    val id: String,
    val title: String,
    val artist: String,
    val album: String = "",
    val artToken: String = "",
    val durationSeconds: Long = 0,
    val playable: Boolean = true,
)

/** Call state as the car's phone page shows it. */
enum class CallState { NONE, INCOMING, ACTIVE, MUTED }

/** A caller, reduced to display strings. */
data class CallerSnapshot(val name: String = "", val number: String = "")

/** The single current call, or null when the phone is idle. */
data class CallSnapshot(
    val state: CallState,
    val caller: CallerSnapshot = CallerSnapshot(),
    val seconds: Long = 0,
)

/** One line of a messaging conversation, oldest first. */
data class ChatLineSnapshot(val sender: String?, val text: String, val at: Long)

/** A notification as the car's message centre shows it, with no PendingIntent or Bitmap. */
data class NotificationSnapshot(
    val key: String,
    val id: Long,
    val packageName: String,
    val appLabel: String,
    val title: String,
    val text: String,
    val postedAt: Long,
    val artToken: String = "",
    val canReply: Boolean = false,
    val replied: Boolean = false,
    val lines: List<ChatLineSnapshot> = emptyList(),
    val isGroup: Boolean = false,
    val read: Boolean = false,
    val burst: Int = 1,
    val shownAt: Long = 0,
)

/** The notification centre: a short history, the popup on screen and the unread count. */
data class NotificationCenterSnapshot(
    val history: List<NotificationSnapshot> = emptyList(),
    val popup: NotificationSnapshot? = null,
    val unread: Int = 0,
    val listening: Boolean = false,
)

/** The next turn from a phone map app, with a plain maneuver code instead of Android constants. */
data class NavigationTurnSnapshot(
    val key: String,
    val packageName: String,
    val distanceText: String,
    val road: String,
    /** A renderer-defined maneuver code; the core's `Maneuver` constants do not cross the boundary. */
    val maneuver: Int,
    val arrowToken: String = "",
)

/** One raw sensor row from the Vehicle data screen. */
data class SensorReadingSnapshot(
    val typeId: Int,
    val typeName: String,
    val raw: String,
    val updatedAtMs: Long,
)

/** A hosted car app the driver can launch, with an icon token instead of a cached Bitmap. */
data class HostedAppSnapshot(
    val packageName: String,
    val label: String,
    val iconToken: String = "",
    val category: String = "",
    val canNavigate: Boolean = false,
)

/** A pure description of a templated screen a hosted app supplied. */
data class TemplateSnapshot(
    val appPackage: String,
    val title: String,
    val rows: List<String> = emptyList(),
    val actions: List<String> = emptyList(),
    val isLoading: Boolean = false,
)

/** Templates and trip state from the app currently owning the surface. */
data class HostedAppSnapshotState(
    val apps: List<HostedAppSnapshot> = emptyList(),
    val template: TemplateSnapshot? = null,
    val navigating: Boolean = false,
    val panMode: Boolean = false,
    val atRoot: Boolean = true,
    /** Increments when the surface a hosted app drew into dies and is replaced. */
    val surfaceEpoch: Int = 0,
)

/** An alert a hosted app asked to show. Timers and callbacks stay in the core. */
data class AlertSnapshot(
    val id: Int,
    val ownerToken: String,
    val title: String,
    val actions: List<String> = emptyList(),
    val durationMs: Long = 0,
)

/** The one toast on screen, plus how many are waiting behind it. */
data class ToastSnapshot(
    val text: String,
    val durationMs: Long,
    val ownerToken: String,
    val waiting: Int = 0,
)

/** An app in the car-app catalog. */
data class CatalogEntrySnapshot(val packageName: String, val label: String, val category: String)

/** Whether a hosted app currently holds a surface lease, and which generation it belongs to. */
data class SurfaceLeaseSnapshot(val granted: Boolean = false, val epoch: Int = 0, val generation: Long = 0)

/** The handful of settings that affect what a renderer may draw. */
data class SettingsSnapshot(
    val night: Boolean = false,
    val scale: Float = 1f,
    val showNotifications: Boolean = true,
    val adaptiveVideo: Boolean = false,
    val pipeAudio: Boolean = false,
)

/**
 * Everything a renderer needs for one screen, assembled from read-only flows.
 *
 * A renderer receives a new immutable value whenever any part changes; it never mutates one or
 * reaches back into the core to change state.
 */
data class SurfaceSnapshot(
    val session: SessionSnapshot = SessionSnapshot(SessionPhase.IDLE),
    val frame: FrameGeometry = FrameGeometry(0, 0, 0, 0, false),
    val insets: ScreenInsets = ScreenInsets.NONE,
    val vehicle: String = "",
    val vehicleModel: VehicleModel? = null,
    val carData: VehicleSnapshot = VehicleSnapshot(),
    val media: MediaSnapshot = MediaSnapshot(),
    val call: CallSnapshot? = null,
    val notifications: NotificationCenterSnapshot = NotificationCenterSnapshot(),
    val navigation: NavigationTurnSnapshot? = null,
    val sensors: List<SensorReadingSnapshot> = emptyList(),
    val hostedApps: HostedAppSnapshotState = HostedAppSnapshotState(),
    val alert: AlertSnapshot? = null,
    val toast: ToastSnapshot? = null,
    val catalog: List<CatalogEntrySnapshot> = emptyList(),
    val lease: SurfaceLeaseSnapshot = SurfaceLeaseSnapshot(),
    val settings: SettingsSnapshot = SettingsSnapshot(),
)
