package app.seb3thehacker.gearslip.surface

import app.seb3thehacker.gearslip.surface.SurfaceTrust

import app.seb3thehacker.gearslip.surface.api.CallSnapshot
import app.seb3thehacker.gearslip.surface.api.CallState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/** A plug-in that throws from every callback must never escape into the session. */
private class ThrowingSurface : HeadunitSurface {
    override val id = "throwing"
    override val capabilities = setOf(
        SurfaceCapability.PROVIDES_FRAMES, SurfaceCapability.TOUCH, SurfaceCapability.KEYS,
    )

    override fun attach(context: SurfaceContext, spec: VideoSpec): FrameSource? =
        throw IllegalStateException("attach")
    override fun detach() = throw IllegalStateException("detach")
    override fun onFocus(projected: Boolean) = throw IllegalStateException("focus")
    override fun onTouch(action: Int, actionIndex: Int, points: List<app.seb3thehacker.gearslip.protocol.TouchPoint>) =
        throw IllegalStateException("touch")
    override fun onKey(keyCode: Int, down: Boolean, longPress: Boolean) = throw IllegalStateException("key")
    override fun onRotate(delta: Int) = throw IllegalStateException("rotate")
}

private class ThrowingCapabilities : HeadunitSurface {
    override val id = "bad-caps"
    override val capabilities: Set<SurfaceCapability> get() = throw IllegalStateException("caps")
}

class SurfaceFailureIsolationTest {

    @Test fun `guard reports a plug-in exception and keeps going`() {
        val seen = mutableListOf<String>()
        val guard = SurfaceGuard { where, error -> seen += "$where:${error.message}" }

        guard.call("onTouch") { throw IllegalStateException("boom") }
        val value = guard.callValue("bitRate", 1_000) { throw IllegalStateException("boom") }

        assertEquals(listOf("onTouch:boom", "bitRate:boom"), seen)
        assertEquals(1_000, value)
    }

    @Test fun `a throwing attach falls back to the placeholder`() {
        val fallback = FakeFrameSource()
        var reported: String? = null
        val guard = SurfaceGuard { where, _ -> reported = where }

        val source = FrameSources.choose(
            ThrowingSurface(), FakeContext(), VideoSpec(800, 480, 30, 160, false), fallback, guard,
        )

        assertSame(fallback, source)
        assertEquals("attach", reported)
    }

    @Test fun `a throwing capabilities getter also falls back to the placeholder`() {
        val fallback = FakeFrameSource()
        var reported: String? = null
        val guard = SurfaceGuard { where, _ -> reported = where }

        val source = FrameSources.choose(
            ThrowingCapabilities(), FakeContext(), VideoSpec(800, 480, 30, 160, false), fallback, guard,
        )

        assertSame(fallback, source)
        assertEquals("capabilities", reported)
    }

    @Test fun `registry generation advances on every swap`() {
        val surface = FakeSurface(emptySet())
        val before = HeadunitSurfaces.generation()
        HeadunitSurfaces.install(surface, SurfaceTrust.TRUSTED)
        val installed = HeadunitSurfaces.generation()
        HeadunitSurfaces.install({ surface }, SurfaceTrust.TRUSTED)
        val factory = HeadunitSurfaces.generation()
        HeadunitSurfaces.clear()
        val cleared = HeadunitSurfaces.generation()

        assertTrue(installed > before)
        assertTrue(factory > installed)
        assertTrue(cleared > factory)
    }

    @Test fun `external registrations are marked untrusted and cleared`() {
        val surface = FakeSurface(emptySet())
        val before = HeadunitSurfaces.generation()
        HeadunitSurfaces.install(surface, SurfaceTrust.UNTRUSTED)
        try {
            assertTrue(HeadunitSurfaces.isExternal(surface))
            assertTrue(HeadunitSurfaces.generation() > before)
        } finally {
            HeadunitSurfaces.clear()
        }
        assertFalse(HeadunitSurfaces.isExternal(surface))
    }

    @Test fun `a renderer swap stops stale state publication`() = runBlocking {
        val sources = FakeSources()
        var swapped = false
        val state = SessionState(3, sources, trusted = true)
        try {
            state.watchGeneration { swapped }
            state.start()
            delay(100)

            swapped = true
            sources.callFlow.value = CallSnapshot(CallState.ACTIVE)
            delay(100)

            assertEquals(null, state.snapshot.value.call)
        } finally {
            state.stop()
        }
    }
}
