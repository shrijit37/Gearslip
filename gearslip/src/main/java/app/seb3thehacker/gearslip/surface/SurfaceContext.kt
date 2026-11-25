package app.seb3thehacker.gearslip.surface

import app.seb3thehacker.gearslip.Insets
import app.seb3thehacker.gearslip.SessionStatus
import app.seb3thehacker.gearslip.car.CarData
import app.seb3thehacker.gearslip.car.CarEnvironment
import kotlinx.coroutines.flow.StateFlow

/**
 * Read-only session state a head-unit surface may observe.
 *
 * Everything here is produced by the protocol core; a surface never pushes state back into it.
 * No Android UI, Compose or View type appears in this contract, so any renderer - Compose, a
 * canvas, a WebView, another process - can implement [HeadunitSurface] without changing the core.
 */
interface SurfaceContext {
    /** Connection phase, for a surface that wants to show a connecting state. */
    val session: StateFlow<SessionStatus.Snapshot>

    /** Frame geometry and density, including the car's real density setting. */
    val frame: StateFlow<CarEnvironment.Frame>

    /** Margins the car crops away; a surface keeps its content inside these. */
    val insets: StateFlow<Insets>

    /** Whether it is dark outside: the car's night sensor, or the time-of-day fallback. */
    val night: StateFlow<Boolean>

    /** Decoded vehicle readings (speed, fuel, location), when the car advertises them. */
    val carData: StateFlow<CarData.Snapshot>

    /** Matched vehicle profile name, for per-car tuning. */
    val vehicle: StateFlow<String>
}
