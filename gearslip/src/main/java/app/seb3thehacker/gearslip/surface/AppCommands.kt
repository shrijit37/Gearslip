package app.seb3thehacker.gearslip.surface

import app.seb3thehacker.gearslip.CarFocus
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.car.CarKeys
import app.seb3thehacker.gearslip.car.CarSettings
import app.seb3thehacker.gearslip.surface.api.CommandSink
import app.seb3thehacker.gearslip.surface.api.SurfaceCommand

/**
 * App-level command sink: the only place the phone UI and trusted renderers go through to change
 * core state. Transport-affecting changes (audio routing, focus) stay auditable here, and a failing
 * core action is contained so it cannot escape into a UI callback or a renderer.
 */
object AppCommands : CommandSink {

    var syncFrame: () -> Unit = {}
    var guard: SurfaceGuard = SurfaceGuard { where, error ->
        GearslipLog.w("command $where threw: ${error.javaClass.simpleName}: ${error.message}")
    }

    override fun submit(command: SurfaceCommand) {
        SurfaceCommands(
            setAudioTransport = { CarSettings.setPipeAudio(it) },
            exitToCar = { CarFocus.exitToCar() },
            requestFocus = { CarFocus.set(it) },
            sendKey = { code, down, long -> CarKeys.onKey(code, down, long) },
            syncFrame = { syncFrame() },
            guard = guard,
        ).submit(command)
    }
}
