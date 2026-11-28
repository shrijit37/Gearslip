package app.seb3thehacker.gearslip.surface

import android.content.Context
import java.util.concurrent.atomic.AtomicLong

/** Who may see the full session snapshot. External renderers get the redacted view. */
enum class SurfaceTrust { TRUSTED, UNTRUSTED }

/** Surface, generation and trust captured together, so a swap cannot split them. */
data class SurfaceSelection(
    val surface: HeadunitSurface?,
    val generation: Long,
    val trusted: Boolean,
)

/**
 * Composition root for the head-unit surface.
 *
 * The phone app installs one factory at startup; the session asks for a fresh surface when it
 * starts. No reflection or service loading: a new UI is a compile-time implementation of
 * [HeadunitSurface] plus one line in the app. Installing null restores the headless placeholder.
 *
 * Every change bumps [generation] inside the same lock as [select]. A session pins the generation
 * and trust from one [SurfaceSelection] and re-checks the generation before publishing state, so a
 * renderer swapped under a running session cannot publish into the new one. Trust is mandatory at
 * install time: an external renderer cannot make itself trusted by omitting an argument.
 */
object HeadunitSurfaces {
    private val generationCounter = AtomicLong(0)

    @Volatile private var factory: ((Context) -> HeadunitSurface)? = null
    @Volatile private var installed: HeadunitSurface? = null
    @Volatile private var trust = SurfaceTrust.UNTRUSTED

    /** Monotonic identity of the current registration. */
    fun generation(): Long = generationCounter.get()

    fun install(factory: ((Context) -> HeadunitSurface)?, trust: SurfaceTrust) = synchronized(this) {
        generationCounter.incrementAndGet()
        this.trust = trust
        installed = null
        this.factory = factory
    }

    fun install(surface: HeadunitSurface?, trust: SurfaceTrust) = synchronized(this) {
        generationCounter.incrementAndGet()
        this.trust = trust
        factory = null
        installed = surface
    }

    /** The instance installed with [install], if any; factories are created per session. */
    val current: HeadunitSurface? get() = installed

    /**
     * Selects the surface, generation and trust in one critical section.
     *
     * A factory is invoked while holding the lock so a swap cannot pair the newly created surface
     * with the old generation or trust bit.
     */
    fun select(context: Context): SurfaceSelection = synchronized(this) {
        // Capture identity and trust BEFORE running plug-in code: a factory that re-registers
        // itself inside the lock must not be able to pair its surface with a newer trusted slot.
        val capturedGeneration = generationCounter.get()
        val capturedTrusted = trust == SurfaceTrust.TRUSTED
        SurfaceSelection(
            surface = factory?.invoke(context.applicationContext) ?: installed,
            generation = capturedGeneration,
            trusted = capturedTrusted,
        )
    }

    /** Kept for callers that only need to know whether the installed renderer is external. */
    fun isExternal(surface: HeadunitSurface?): Boolean = synchronized(this) {
        if (surface == null || trust == SurfaceTrust.TRUSTED) return@synchronized false
        // An untrusted factory also makes its per-session surfaces untrusted.
        installed === surface || factory != null
    }

    fun clear() = synchronized(this) {
        generationCounter.incrementAndGet()
        trust = SurfaceTrust.UNTRUSTED
        factory = null
        installed = null
    }
}
