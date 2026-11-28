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
        val provided = guard?.callValue("attach", null) { surface?.attach(context, spec) }
            ?: if (guard == null) surface?.attach(context, spec) else null
        return if (provided != null && surface?.capabilities?.contains(SurfaceCapability.PROVIDES_FRAMES) == true) {
            provided
        } else {
            fallback
        }
    }
}
