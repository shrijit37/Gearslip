package app.seb3thehacker.gearslip.surface

import app.seb3thehacker.gearslip.protocol.TouchPoint
import app.seb3thehacker.gearslip.surface.pixel.PixelLeaseProvider

/** What a surface can accept, declared up front so the core never assumes a renderer exists. */
enum class SurfaceCapability {
    /** The surface produces its own frames instead of using the generated placeholder. */
    PROVIDES_FRAMES,

    /** The surface draws pixels into an encoder-backed lease supplied by the core. */
    PROVIDES_PIXELS,

    /** Head-unit touches should be forwarded with [HeadunitSurface.onTouch]. */
    TOUCH,

    /** Steering-wheel and knob input should be forwarded with [HeadunitSurface.onKey]/[onRotate]. */
    KEYS,
}

/**
 * Plug-in point for anything that draws on the head unit.
 *
 * The AAP session owns the connection and the frame clock; a surface owns pixels and input. Add a
 * new head-unit UI by implementing this interface and registering it at the app composition root
 * ([HeadunitSurfaces.install]) - the protocol core needs no changes. With no surface installed the
 * session still runs, sending the placeholder frame and dropping input.
 *
 * Callbacks may arrive on the session's background thread. Implementations that touch Android UI
 * must dispatch to their own main thread; the core deliberately knows nothing about handlers.
 */
interface HeadunitSurface {
    /** Contract version for a future negotiation step; no refusal logic exists yet. */
    val version: Int get() = 1

    val id: String
    val capabilities: Set<SurfaceCapability>

    /**
     * Called once per session, before frames start. Return a [FrameSource] when [PROVIDES_FRAMES]
     * is declared; the returned source is started and stopped by the session.
     */
    fun attach(context: SurfaceContext, spec: VideoSpec): FrameSource? = null

    /** Supplies the Surface the core lends to hosted car apps; the renderer owns the lease. */
    fun pixelLeaseProvider(): PixelLeaseProvider? = null

    /** The session ended; release anything the surface holds. */
    fun detach() {}

    /** The car took its screen back, or handed it over again. */
    fun onFocus(projected: Boolean) {}

    /** A head-unit touch, in negotiated frame coordinates. */
    fun onTouch(action: Int, actionIndex: Int, points: List<TouchPoint>) {}

    /**
     * A steering-wheel or hard key edge. A trusted surface returning true consumes it, so the core
     * leaves the key alone and the renderer can own its own key map. Untrusted surfaces cannot
     * suppress core handling; returning false (the default) keeps media/call/screen behaviour.
     */
    fun onKey(keyCode: Int, down: Boolean, longPress: Boolean): Boolean = false

    /** One rotary-knob click; positive turns clockwise. */
    fun onRotate(delta: Int) {}
}
