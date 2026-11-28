package app.seb3thehacker.gearslip.surface

/**
 * Selects the encoder's frame source for a session.
 *
 * Kept separate from the runner so the choice is a pure, testable decision: a surface is attached
 * once, and only a surface that declares [SurfaceCapability.PROVIDES_FRAMES] and actually returns
 * a source replaces the placeholder. A throwing [HeadunitSurface.attach] falls back to the
 * placeholder instead of taking the session down with it.
 */
internal object FrameSources {
    fun choose(
        surface: HeadunitSurface?,
        context: SurfaceContext,
        spec: VideoSpec,
        fallback: FrameSource,
        guard: SurfaceGuard? = null,
    ): FrameSource {
        // Exactly one attach call whether or not a failure guard was supplied.
        val provided = if (guard != null) {
            guard.callValue("attach", null) { surface?.attach(context, spec) }
        } else {
            surface?.attach(context, spec)
        }
        // capabilities is plug-in code as well; a throwing getter must not escape the guard.
        val providesFrames = if (guard != null) {
            guard.callValue("capabilities", false) {
                surface?.capabilities?.contains(SurfaceCapability.PROVIDES_FRAMES) == true
            }
        } else {
            surface?.capabilities?.contains(SurfaceCapability.PROVIDES_FRAMES) == true
        }
        return if (provided != null && providesFrames) {
            provided
        } else {
            fallback
        }
    }
}
