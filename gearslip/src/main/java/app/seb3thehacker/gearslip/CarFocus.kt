package app.seb3thehacker.gearslip

import app.seb3thehacker.gearslip.surface.SessionOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Adapter for the attached session's focus state.
 *
 * The mutable state and the exit handler live in [SessionOwner], one per session. A renderer or a
 * stale session can no longer change focus or ask the car to take the screen back: every mutation
 * goes through the owner's generation check. With no session attached the adapter reports the
 * car as projected and silently ignores requests.
 */
object CarFocus {

    @Volatile private var owner: SessionOwner? = null
    private val detached = MutableStateFlow(true)

    /** False while the car shows its own interface. */
    val projected: StateFlow<Boolean> get() = owner?.projected ?: detached

    /** Attaches the owner created for this session. */
    fun attach(owner: SessionOwner) {
        this.owner = owner
    }

    /** Detaches and closes the owner so no late focus change can reach a newer session. */
    fun detach(owner: SessionOwner) {
        if (this.owner === owner) {
            owner.close()
            this.owner = null
        }
    }

    /** Installs the native exit handler on the current session only. */
    fun setExitHandler(handler: (() -> Unit)?) {
        val current = owner ?: return
        current.setExitHandler(current.generation, handler)
    }

    /** Asks the car to show its own interface. Does nothing when no car is connected. */
    fun exitToCar() {
        val current = owner ?: return
        current.requestExit(current.generation)
    }

    internal fun set(projected: Boolean) {
        owner?.let { it.setProjected(it.generation, projected) }
    }
}
