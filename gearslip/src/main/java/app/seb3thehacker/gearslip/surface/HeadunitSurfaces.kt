package app.seb3thehacker.gearslip.surface

import android.content.Context

/**
 * Composition root for the head-unit surface.
 *
 * The phone app installs one factory at startup; the session asks for a fresh surface when it
 * starts. No reflection or service loading: a new UI is a compile-time implementation of
 * [HeadunitSurface] plus one line in the app. Installing null restores the headless placeholder.
 */
object HeadunitSurfaces {
    @Volatile private var factory: ((Context) -> HeadunitSurface)? = null
    @Volatile private var installed: HeadunitSurface? = null

    fun install(factory: ((Context) -> HeadunitSurface)?) {
        installed = null
        this.factory = factory
    }

    fun install(surface: HeadunitSurface?) {
        factory = null
        installed = surface
    }

    /** The instance installed with [install], if any; factories are created per session. */
    val current: HeadunitSurface? get() = installed

    /** The surface this session should use, or null when none is installed. */
    fun create(context: Context): HeadunitSurface? =
        factory?.invoke(context.applicationContext) ?: installed

    fun clear() {
        factory = null
        installed = null
    }
}
