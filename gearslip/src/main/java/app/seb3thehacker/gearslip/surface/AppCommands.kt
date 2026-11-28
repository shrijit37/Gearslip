package app.seb3thehacker.gearslip.surface

import app.seb3thehacker.gearslip.CarFocus
import app.seb3thehacker.gearslip.car.CarKeys
import app.seb3thehacker.gearslip.car.CarSettings
import app.seb3thehacker.gearslip.surface.api.CommandSink
import app.seb3thehacker.gearslip.surface.api.SurfaceCommand

/**
 * App-level command sink: the only place the phone UI and trusted renderers go through to change
 * core state. Transport-affecting changes (audio routing, focus) stay auditable here.
 */
object AppCommands : CommandSink {

    var syncFrame: () -> Unit = {}

    private val sink = SurfaceCommands(
        setAudioTransport = { CarSettings.setPipeAudio(it) },
        exitToCar = { CarFocus.exitToCar() },
        requestFocus = { CarFocus.set(it) },
        sendKey = { code, down, long -> CarKeys.onKey(code, down, long) },
        syncFrame = { syncFrame() },
    )

    override fun submit(command: SurfaceCommand) = sink.submit(command)
}
