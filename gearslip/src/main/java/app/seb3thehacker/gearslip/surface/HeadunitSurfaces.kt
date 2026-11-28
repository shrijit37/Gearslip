package app.seb3thehacker.gearslip.surface

import android.content.Context
import java.util.concurrent.atomic.AtomicLong

/**
 * Composition root for the head-unit surface.
 *
 * The phone app installs one factory at startup; the session asks for a fresh surface when it
 * starts. No reflection or service loading: a new UI is a compile-time implementation of
 * [HeadunitSurface] plus one line in the app. Installing null restores the headless placeholder.
 *
 * Every change bumps [generation]. A session pins the generation when it attaches and re-checks
 * it before publishing state, mirroring the updater's generation rule: a renderer swapped under a
 * running session cannot publish into the new one.
 */
object HeadunitSurfaces {
    private val generation = AtomicLong(0)

    @Volatile private var factory: ((Context) -> HeadunitSurface)? = null
    @Volatile private var installed: HeadunitSurface? = null

    /** Monotonic identity of the current registration. */
    fun generation(): Long = generation.get()

    fun install(factory: ((Context) -> HeadunitSurface)?) {
        generation.incrementAndGet()
        installed = null
        this.factory = factory
    }

    fun install(surface: HeadunitSurface?) {
        generation.incrementAndGet()
        factory = null
        installed = surface
    }

    /** The instance installed with [install], if any; factories are created per session. */
    val current: HeadunitSurface? get() = installed

    /** The surface this session should use, or null when none is installed. */
    fun create(context: Context): HeadunitSurface? =
        factory?.invoke(context.applicationContext) ?: installed

    fun clear() {
        generation.incrementAndGet()
        factory = null
        installed = null
    }
}
