package app.seb3thehacker.gearslip.surface

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

/**
 * Holds the derived flows that project process-global owners into API values.
 *
 * The collectors run only while a session is subscribed (`WhileSubscribed`), so they stop when the
 * session ends instead of mirroring state forever on a background thread.
 */
internal object StateFlows {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun <T> Flow<T>.startingWith(initial: T): StateFlow<T> =
        stateIn(scope, SharingStarted.WhileSubscribed(5_000), initial)
}
