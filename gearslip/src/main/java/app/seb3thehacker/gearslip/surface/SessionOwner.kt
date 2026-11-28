package app.seb3thehacker.gearslip.surface

import app.seb3thehacker.gearslip.surface.api.SessionPhase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The single owner of mutable session state.
 *
 * One instance is created per session with a generation. Every mutation must present that
 * generation, so work started by a previous session (including a renderer that kept a reference)
 * cannot change the state of a newer one. Plug-ins only ever receive read-only snapshots; they
 * never hold this owner.
 */
class SessionOwner(val generation: Long) {

    private val _phase = MutableStateFlow(SessionPhase.IDLE)
    val phase: StateFlow<SessionPhase> = _phase.asStateFlow()

    private val _projected = MutableStateFlow(true)
    val projected: StateFlow<Boolean> = _projected.asStateFlow()

    /** True when a stale session tried to mutate this owner; used by the core to log and stop it. */
    private val _rejected = MutableStateFlow(0)
    val rejected: StateFlow<Int> = _rejected.asStateFlow()

    fun setPhase(expectedGeneration: Long, phase: SessionPhase): Boolean {
        if (!authorized(expectedGeneration)) return false
        _phase.value = phase
        return true
    }

    fun setProjected(expectedGeneration: Long, projected: Boolean): Boolean {
        if (!authorized(expectedGeneration)) return false
        _projected.value = projected
        return true
    }

    @Volatile private var exitHandler: (() -> Unit)? = null

    /** Installs the core's exit-to-car handler for this session only. */
    fun setExitHandler(expectedGeneration: Long, handler: (() -> Unit)?) {
        if (authorized(expectedGeneration)) exitHandler = handler
    }

    /** Asks the car to show its own interface; refused once this session is closed. */
    fun requestExit(expectedGeneration: Long): Boolean {
        if (!authorized(expectedGeneration)) return false
        exitHandler?.invoke()
        return true
    }

    /** Ends this session: later mutations are refused even if the generation happens to match. */
    fun close() {
        closed = true
        exitHandler = null
    }

    @Volatile private var closed = false

    private fun authorized(expectedGeneration: Long): Boolean {
        if (!closed && expectedGeneration == generation) return true
        _rejected.value++
        return false
    }
}
