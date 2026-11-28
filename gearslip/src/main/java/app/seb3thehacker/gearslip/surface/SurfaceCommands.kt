package app.seb3thehacker.gearslip.surface

import app.seb3thehacker.gearslip.surface.api.CommandSink
import app.seb3thehacker.gearslip.surface.api.SurfaceCommand

/**
 * The one entry point for state-changing requests from a renderer or the phone UI.
 *
 * The core actions are injected so this layer is a pure JVM-testable decision: callers never reach
 * a core singleton directly, and a plug-in failure while submitting a command cannot escape.
 */
class SurfaceCommands(
    private val setAudioTransport: (Boolean) -> Unit = {},
    private val exitToCar: () -> Unit = {},
    private val requestFocus: (Boolean) -> Unit = {},
    private val sendKey: (Int, Boolean, Boolean) -> Unit = { _, _, _ -> },
    private val syncFrame: () -> Unit = {},
    private val guard: SurfaceGuard = SurfaceGuard { _, _ -> },
) : CommandSink {

    override fun submit(command: SurfaceCommand) {
        val run = { body: () -> Unit -> guard.call(command.javaClass.simpleName, body) }
        when (command) {
            is SurfaceCommand.SetAudioTransport -> run { setAudioTransport(command.pipe) }
            SurfaceCommand.ExitToCar -> run(exitToCar)
            is SurfaceCommand.RequestFocus -> run { requestFocus(command.projected) }
            is SurfaceCommand.SendKey -> run { sendKey(command.keyCode, command.down, command.longPress) }
            SurfaceCommand.SyncFrame -> run(syncFrame)
        }
    }
}
