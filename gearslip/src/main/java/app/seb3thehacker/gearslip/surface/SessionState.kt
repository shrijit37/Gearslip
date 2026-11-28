package app.seb3thehacker.gearslip.surface

import app.seb3thehacker.gearslip.surface.api.FrameGeometry
import app.seb3thehacker.gearslip.surface.api.FrameSize
import app.seb3thehacker.gearslip.surface.api.SurfaceSnapshot
import app.seb3thehacker.gearslip.surface.api.SurfaceLeaseSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * One session's read-only view of the car, owned by the session rather than by a plug-in.
 *
 * The process-wide owners still hold the mutable state for now; this class is the session-scoped
 * boundary a renderer reads. Each session gets its own generation, snapshot flow and collector
 * job, so a stale session cannot publish into a newer one and stopping cancels its collectors.
 * A plug-in receives only [snapshot] — it has no reference to the state owners or the transport.
 */
class SessionState(
    val generation: Long,
    private val sources: SessionSources = GlobalSources,
    /** False for external/untrusted plug-ins: they receive the redacted snapshot only. */
    val trusted: Boolean,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val _snapshot = MutableStateFlow(initialSnapshot())

    private fun initialSnapshot(): SurfaceSnapshot {
        val value = SurfaceSnapshot(
            session = sources.session.value,
            frame = sources.frame.value.let { FrameGeometry(it.width, it.height, 30, it.densityDpi, false) },
            insets = sources.insets.value,
            night = sources.night.value,
            vehicle = sources.vehicle.value,
            vehicleModel = sources.vehicleModel.value,
            carData = sources.carData.value,
            media = sources.media.value,
            call = sources.call.value,
            notifications = sources.notifications.value,
            navigation = sources.navigation.value,
            sensors = sources.sensors.value,
            hostedApps = sources.hostedApps.value,
            alert = sources.alert.value,
            toast = sources.toast.value,
            catalog = sources.catalog.value,
            lease = sources.lease.value.copy(generation = generation),
            settings = sources.settings.value,
        )
        return if (trusted) value else value.redacted()
    }
    /** The negotiated video format is known only to the runner, not to the geometry owner. */
    private val videoSpec = MutableStateFlow<VideoSpec?>(null)
    /** True once the renderer registration this session attached to has been replaced. */
    @Volatile private var generationStale: () -> Boolean = { false }

    /** Pins the registration generation; a swap stops this session before it publishes again. */
    fun watchGeneration(isStale: () -> Boolean) {
        generationStale = isStale
    }

    /** The complete session view. Every emission is an immutable value. */
    val snapshot: StateFlow<SurfaceSnapshot> = _snapshot.asStateFlow()

    /** Starts mirroring the source flows. Safe to call once per session. */
    fun start() {
        mirror(sources.session) { copy(session = it) }
        // Merge geometry with the negotiated spec so frameRate and wireless are real values,
        // not placeholders invented by the source that only knows width/height/density.
        scope.launch {
            combine(sources.frame, videoSpec) { size, spec ->
                val effective = spec?.let {
                    FrameSize(it.width, it.height, it.densityDpi)
                } ?: size
                FrameGeometry(
                    effective.width, effective.height,
                    spec?.frameRate ?: 30, effective.densityDpi, spec?.wireless ?: false,
                )
            }.collect { value ->
                if (publishable()) _snapshot.update { current ->
                    val next = current.copy(frame = value)
                    if (trusted) next else next.redacted()
                }
            }
        }
        mirror(sources.insets) { copy(insets = it) }
        mirror(sources.night) { copy(night = it) }
        mirror(sources.vehicle) { copy(vehicle = it) }
        mirror(sources.vehicleModel) { copy(vehicleModel = it) }
        mirror(sources.carData) { copy(carData = it) }
        mirror(sources.media) { copy(media = it) }
        mirror(sources.call) { copy(call = it) }
        mirror(sources.notifications) { copy(notifications = it) }
        mirror(sources.navigation) { copy(navigation = it) }
        mirror(sources.sensors) { copy(sensors = it) }
        mirror(sources.hostedApps) { copy(hostedApps = it) }
        mirror(sources.alert) { copy(alert = it) }
        mirror(sources.toast) { copy(toast = it) }
        mirror(sources.catalog) { copy(catalog = it) }
        mirror(sources.lease) { copy(lease = it.copy(generation = generation)) }
        mirror(sources.settings) { copy(settings = it) }
    }

    /** Publishes the format the car negotiated for this session. */
    fun setVideoSpec(spec: VideoSpec) {
        videoSpec.value = spec
    }

    /** Ends the session: collectors stop and no later source change can publish. */
    fun stop() {
        job.cancel()
    }

    /** Re-checks liveness and the pinned generation before any state can be published. */
    private fun publishable(): Boolean {
        if (!job.isActive) return false
        if (!generationStale()) return true
        stop()
        return false
    }

    private fun <T> mirror(flow: StateFlow<T>, updateWith: SurfaceSnapshot.(T) -> SurfaceSnapshot) {
        scope.launch {
            flow.collect { value ->
                if (publishable()) {
                    _snapshot.update { current ->
                        val next = current.updateWith(value)
                        if (trusted) next else next.redacted()
                    }
                }
            }
        }
    }
}
