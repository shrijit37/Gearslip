package app.seb3thehacker.gearslip.surface

/** Where a plug-in failure was caught, for the core's log. */
fun interface SurfaceFailureReporter {
    fun report(where: String, error: Throwable)
}

/**
 * Keeps a plug-in from ending a drive.
 *
 * Every callback into a surface or its frame source goes through [call]/[callValue]. A throwing
 * renderer is logged and ignored; the session, transport and installer are never involved. Fatal
 * JVM conditions (thread death, VM errors) are rethrown because no plug-in can recover from them.
 */
class SurfaceGuard(private val reporter: SurfaceFailureReporter) {

    fun call(where: String, body: () -> Unit) {
        try {
            body()
        } catch (t: Throwable) {
            if (isFatal(t)) throw t
            reporter.report(where, t)
        }
    }

    fun <T> callValue(where: String, fallback: T, body: () -> T): T = try {
        body()
    } catch (t: Throwable) {
        if (isFatal(t)) throw t
        reporter.report(where, t)
        fallback
    }

    private fun isFatal(t: Throwable) = t is ThreadDeath || t is VirtualMachineError
}
