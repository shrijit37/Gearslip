package app.seb3thehacker.gearslip.surface

import app.seb3thehacker.gearslip.surface.api.SurfaceSnapshot
import app.seb3thehacker.gearslip.surface.api.SurfaceLeaseSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    private val trusted: Boolean = true,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)
    private val _snapshot = MutableStateFlow(
        SurfaceSnapshot(lease = SurfaceLeaseSnapshot(generation = generation)),
    )

    /** The complete session view. Every emission is an immutable value. */
    val snapshot: StateFlow<SurfaceSnapshot> = _snapshot.asStateFlow()

    /** Starts mirroring the source flows. Safe to call once per session. */
    fun start() {
        mirror(sources.session) { copy(session = it) }
        mirror(sources.frame) { copy(frame = it) }
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

    /** Ends the session: collectors stop and no later source change can publish. */
    fun stop() {
        job.cancel()
    }

    private fun <T> mirror(flow: StateFlow<T>, updateWith: SurfaceSnapshot.(T) -> SurfaceSnapshot) {
        scope.launch {
            flow.collect { value ->
                if (job.isActive) {
                    _snapshot.update { current ->
                        val next = current.updateWith(value)
                        if (trusted) next else next.redacted()
                    }
                }
            }
        }
    }
}
