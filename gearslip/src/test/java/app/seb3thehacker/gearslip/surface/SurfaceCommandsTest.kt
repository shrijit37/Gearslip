package app.seb3thehacker.gearslip.surface

import app.seb3thehacker.gearslip.surface.api.SurfaceCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The command layer is a pure JVM decision: a renderer never reaches a core singleton. */
class SurfaceCommandsTest {

    @Test fun `every command routes to its core action`() {
        val calls = mutableListOf<String>()
        val sink = SurfaceCommands(
            setAudioTransport = { calls += "audio:$it" },
            exitToCar = { calls += "exit" },
            requestFocus = { calls += "focus:$it" },
            sendKey = { code, down, long -> calls += "key:$code:$down:$long" },
            syncFrame = { calls += "sync" },
        )

        sink.submit(SurfaceCommand.SetAudioTransport(false))
        sink.submit(SurfaceCommand.ExitToCar)
        sink.submit(SurfaceCommand.RequestFocus(true))
        sink.submit(SurfaceCommand.SendKey(21, true, false))
        sink.submit(SurfaceCommand.SyncFrame)

        assertEquals(
            listOf("audio:false", "exit", "focus:true", "key:21:true:false", "sync"),
            calls,
        )
    }

    @Test fun `a throwing core action is reported and does not escape`() {
        val seen = mutableListOf<String>()
        val sink = SurfaceCommands(
            setAudioTransport = { throw IllegalStateException("audio unavailable") },
            guard = SurfaceGuard { where, error -> seen += "$where:${error.message}" },
        )

        sink.submit(SurfaceCommand.SetAudioTransport(true))

        assertEquals(1, seen.size)
        assertTrue(seen.first().endsWith("audio unavailable"))
    }

    @Test fun `the default sink refuses every command`() {
        val defaults = SurfaceCommands()
        // No injected action throws, so all commands are safely no-ops.
        defaults.submit(SurfaceCommand.ExitToCar)
        defaults.submit(SurfaceCommand.SyncFrame)
    }

    @Test fun `a surface declares a contract version`() {
        assertEquals(1, FakeSurface(emptySet()).version)
    }
}
