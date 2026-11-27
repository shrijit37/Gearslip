package app.seb3thehacker.gearslip.car

import android.content.Context
import app.seb3thehacker.gearslip.CertProvider
import app.seb3thehacker.gearslip.GearslipLog
import java.util.concurrent.Executors

/**
 * Unlocks the certificate ahead of time, so a head unit's first message is answered at once.
 *
 * Safe to call as often as you like: calls closer together than [MIN_GAP_MS] are dropped.
 */
object Prefetch {

    private const val MIN_GAP_MS = 60_000L

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "gearslip-prefetch").apply { isDaemon = true } }
    @Volatile private var lastRun = 0L

    /** [force] skips the throttle; use it when something changed, such as a permission grant. */
    fun warm(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastRun < MIN_GAP_MS) return
        lastRun = now
        val app = context.applicationContext
        worker.execute {
            val start = System.currentTimeMillis()
            step("certificate") { CertProvider.warm(app) }
            GearslipLog.i("certificate warmed in ${System.currentTimeMillis() - start}ms")
        }
    }

    // One failing loader must not stop the others.
    private fun step(name: String, block: () -> Unit) {
        runCatching(block).onFailure { GearslipLog.w("prefetch: $name failed: ${it.message}") }
    }
}
