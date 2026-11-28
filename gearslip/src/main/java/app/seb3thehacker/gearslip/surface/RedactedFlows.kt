package app.seb3thehacker.gearslip.surface

import app.seb3thehacker.gearslip.Insets
import app.seb3thehacker.gearslip.SessionStatus
import app.seb3thehacker.gearslip.car.CarData
import app.seb3thehacker.gearslip.car.CarEnvironment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Live, redacted views of the raw `SurfaceContext` flows for untrusted renderers.
 *
 * A one-shot copy would freeze the flow; these transforms keep emitting while the surface is
 * subscribed. Sensitive values go to safe defaults rather than an opt-out list, so a field added
 * to a core snapshot later cannot leak by default.
 */
internal object RedactedFlows {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sharing = SharingStarted.WhileSubscribed(5_000)

    fun session(source: StateFlow<SessionStatus.Snapshot>): StateFlow<SessionStatus.Snapshot> {
        // Allowlist: only structural phase and link hint are safe to expose. A new field on the
        // core snapshot is absent here until someone deliberately adds it.
        fun safe(value: SessionStatus.Snapshot) = SessionStatus.Snapshot(
            phase = value.phase,
            headline = "",
            detail = "",
            stuckOn = value.stuckOn,
        )
        return source.map(::safe).stateIn(scope, sharing, safe(source.value))
    }

    fun frame(source: StateFlow<CarEnvironment.Frame>): StateFlow<CarEnvironment.Frame> = source

    fun insets(source: StateFlow<Insets>): StateFlow<Insets> = source

    fun night(source: StateFlow<Boolean>): StateFlow<Boolean> = source

    /** Speed, fuel, compass and GPS all go: a renderer needs none of them to lay out a screen. */
    fun carData(source: StateFlow<CarData.Snapshot>): StateFlow<CarData.Snapshot> =
        source.map { CarData.Snapshot() }.stateIn(scope, sharing, CarData.Snapshot())

    fun vehicle(source: StateFlow<String>): StateFlow<String> =
        source.map { "" }.stateIn(scope, sharing, "")
}
