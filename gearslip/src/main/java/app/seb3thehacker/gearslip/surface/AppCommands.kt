package app.seb3thehacker.gearslip.surface

import app.seb3thehacker.gearslip.CarFocus
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.car.CarKeys
import app.seb3thehacker.gearslip.car.CarSettings
import app.seb3thehacker.gearslip.surface.api.CommandSink
import app.seb3thehacker.gearslip.surface.api.SurfaceCommand

/**
 * Builds the app-level command sinks. Each session gets its own sink: no global mutable state can
 * let a released session disable or redirect a live one.
 *
 * `RequestFocus` is deliberately not wired to `CarFocus.projected` — that flag is controlled by the
 * head unit's video-focus protocol, and a renderer overriding it would contradict the car.
 */
object AppCommands {

    private val logGuard = SurfaceGuard { where, error ->
        GearslipLog.w("command $where threw: ${error.javaClass.simpleName}: ${error.message}")
    }

    /** Sink for the phone UI, which has no session-owned sync-frame handler. */
    internal val uiSink: CommandSink = buildSink(logGuard) {}

    internal fun forSession(guard: SurfaceGuard, syncFrame: () -> Unit): CommandSink = buildSink(guard, syncFrame)

    private fun buildSink(guard: SurfaceGuard, syncFrame: () -> Unit) = SurfaceCommands(
        setAudioTransport = { CarSettings.setPipeAudio(it) },
        exitToCar = { CarFocus.exitToCar() },
        requestFocus = { _ ->
            // Deliberately ignored, with a log: focus follows the car's video-focus messages.
            // Letting a renderer write CarFocus.projected contradicts the head unit's own state.
            GearslipLog.i("command: focus request ignored; the car owns video focus")
        },
        sendKey = { code, down, long -> CarKeys.onKey(code, down, long) },
        syncFrame = syncFrame,
        guard = guard,
    )
}
