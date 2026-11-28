package app.seb3thehacker.gearslip.update

/**
 * Pure decision state for an update flow: one generation and two queued install intents.
 *
 * The updater is safety-critical: a channel or dev-ref change must invalidate work already in
 * flight, and an install tap must not survive a target change or be dropped if the install returns
 * early. Keeping those rules here lets them be tested on a JVM without Android stubs.
 */
class UpdateDecision {
    @Volatile var generation: Int = 0
        private set

    @Volatile private var installWhenAllowed = false
    @Volatile private var installWhenCarGone = false

    /** Invalidates every check/download started against an older target. */
    @Synchronized fun reset() {
        generation++
        installWhenAllowed = false
        installWhenCarGone = false
    }

    fun isCurrent(observed: Int): Boolean = observed == generation

    fun queueWhenAllowed() { synchronized(this) { installWhenAllowed = true } }
    fun hasAllowed(): Boolean = synchronized(this) { installWhenAllowed }

    /** Takes the queued "allowed" intent once, so it cannot fire twice. */
    fun consumeAllowed(): Boolean = synchronized(this) {
        val value = installWhenAllowed
        installWhenAllowed = false
        value
    }

    fun clearAllowed() { synchronized(this) { installWhenAllowed = false } }

    fun queueWhenCarGone() { synchronized(this) { installWhenCarGone = true } }
    fun hasCarGone(): Boolean = synchronized(this) { installWhenCarGone }
    fun clearCarGone() { synchronized(this) { installWhenCarGone = false } }

    fun clearAll() { synchronized(this) { installWhenAllowed = false; installWhenCarGone = false } }
}
