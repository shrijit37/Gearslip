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
    @Volatile private var external = false

    /** Monotonic identity of the current registration. */
    fun generation(): Long = generation.get()

    fun install(factory: ((Context) -> HeadunitSurface)?) {
        generation.incrementAndGet()
        external = false
        installed = null
        this.factory = factory
    }

    fun install(surface: HeadunitSurface?, externallyProvided: Boolean = false) {
        generation.incrementAndGet()
        external = externallyProvided
        factory = null
        installed = surface
    }

    /** True when [surface] is the currently installed external (untrusted) renderer. */
    fun isExternal(surface: HeadunitSurface?): Boolean = external && surface != null && installed === surface

    /** Selects the surface and the generation it was selected at, atomically. */
    fun createWithGeneration(context: Context): Pair<HeadunitSurface?, Long> = synchronized(this) {
        create(context) to generation()
    }

    /** The instance installed with [install], if any; factories are created per session. */
    val current: HeadunitSurface? get() = installed

    /** The surface this session should use, or null when none is installed. */
    fun create(context: Context): HeadunitSurface? =
        factory?.invoke(context.applicationContext) ?: installed

    fun clear() {
        generation.incrementAndGet()
        external = false
        factory = null
        installed = null
    }
}
