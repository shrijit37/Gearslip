package app.seb3thehacker.gearslip.surface

/**
 * Selects the encoder's frame source for a session.
 *
 * Kept separate from the runner so the choice is a pure, testable decision: a surface is attached
 * once, and only a surface that declares [SurfaceCapability.PROVIDES_FRAMES] and actually returns
 * a source replaces the placeholder.
 */
internal object FrameSources {
    fun choose(
        surface: HeadunitSurface?,
        context: SurfaceContext,
        spec: VideoSpec,
        fallback: FrameSource,
    ): FrameSource {
        val provided = surface?.attach(context, spec)
        return if (provided != null && surface.capabilities.contains(SurfaceCapability.PROVIDES_FRAMES)) {
            provided
        } else {
            fallback
        }
    }
}
