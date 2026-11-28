package app.seb3thehacker.gearslip.surface.api

/**
 * The one way a surface asks the core to change something.
 *
 * A renderer submits commands; it never calls a core singleton or an Android API to do it. This
 * keeps untrusted plug-ins off the transport and gives the core a single place to apply safety
 * rules (focus, audio transport, key interception). Key codes are plain integers, not
 * `KeyEvent` constants, so the contract stays usable off Android.
 */
sealed interface SurfaceCommand {

    /** Give the car its screen back. */
    data object ExitToCar : SurfaceCommand

    /** Route audio through the car's pipe (true) or the phone's speaker (false). */
    data class SetAudioTransport(val pipe: Boolean) : SurfaceCommand

    /** Tell the core whether the projected surface currently has focus. */
    data class RequestFocus(val projected: Boolean) : SurfaceCommand

    /** A steering-wheel or hard-key edge, expressed as a plain key code. */
    data class SendKey(val keyCode: Int, val down: Boolean, val longPress: Boolean) : SurfaceCommand

    /** Ask the encoder to emit a keyframe so a renderer can resynchronise. */
    data object SyncFrame : SurfaceCommand
}

/** Where a surface sends its commands. Implemented by the core, never by the renderer itself. */
fun interface CommandSink {
    fun submit(command: SurfaceCommand)
}
