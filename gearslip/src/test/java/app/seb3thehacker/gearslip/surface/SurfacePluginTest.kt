package app.seb3thehacker.gearslip.surface

import app.seb3thehacker.gearslip.CertProvider
import app.seb3thehacker.gearslip.GearslipRunner
import app.seb3thehacker.gearslip.Insets
import app.seb3thehacker.gearslip.Protobuf
import app.seb3thehacker.gearslip.SessionStatus
import app.seb3thehacker.gearslip.SelfSignedCert
import app.seb3thehacker.gearslip.car.CarData
import app.seb3thehacker.gearslip.car.CarEnvironment
import app.seb3thehacker.gearslip.protocol.TouchPoint
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

private class FakeFrameSource : FrameSource {
    override var bitRate = 1_000
    override var fullBitRate = 2_000
    var starts = 0
    var output: FrameOutput? = null

    override fun start(output: FrameOutput) { starts++; this.output = output }
    override fun requestSyncFrame() = Unit
    override fun applyBitRate(bps: Int) { bitRate = bps }
    override fun stop() = Unit
}

private class FakeSurface(
    override val capabilities: Set<SurfaceCapability>,
    private val provided: FrameSource? = null,
) : HeadunitSurface {
    override val id = "fake-surface"
    var attaches = 0
    var detaches = 0
    var focus: Boolean? = null
    val touches = mutableListOf<Triple<Int, Int, List<TouchPoint>>>()
    val keys = mutableListOf<Triple<Int, Boolean, Boolean>>()
    val rotations = mutableListOf<Int>()

    override fun attach(context: SurfaceContext, spec: VideoSpec): FrameSource? {
        attaches++
        assertEquals(800, spec.width)
        return provided
    }

    override fun detach() { detaches++ }
    override fun onFocus(projected: Boolean) { focus = projected }
    override fun onTouch(action: Int, actionIndex: Int, points: List<TouchPoint>) {
        touches += Triple(action, actionIndex, points)
    }

    override fun onKey(keyCode: Int, down: Boolean, longPress: Boolean) {
        keys += Triple(keyCode, down, longPress)
    }

    override fun onRotate(delta: Int) { rotations += delta }
}

private class FakeContext : SurfaceContext {
    override val session = MutableStateFlow(SessionStatus.state.value)
    override val frame = MutableStateFlow(CarEnvironment.Frame(800, 480, 160))
    override val insets = MutableStateFlow(Insets.NONE)
    override val night = MutableStateFlow(false)
    override val carData = MutableStateFlow(CarData.Snapshot())
    override val vehicle = MutableStateFlow("test car")
}

class SurfacePluginTest {

    @Test fun `a surface with PROVIDES_FRAMES replaces the placeholder`() {
        val provided = FakeFrameSource()
        val surface = FakeSurface(setOf(SurfaceCapability.PROVIDES_FRAMES), provided)
        val fallback = FakeFrameSource()
        val spec = VideoSpec(800, 480, 30, 160, false)

        assertSame(provided, FrameSources.choose(surface, FakeContext(), spec, fallback))
        assertEquals(1, surface.attaches)
        assertEquals(0, fallback.starts)
    }

    @Test fun `a surface without PROVIDES_FRAMES keeps the placeholder`() {
        val surface = FakeSurface(setOf(SurfaceCapability.TOUCH), FakeFrameSource())
        val fallback = FakeFrameSource()
        val spec = VideoSpec(800, 480, 30, 160, false)

        assertSame(fallback, FrameSources.choose(surface, FakeContext(), spec, fallback))
        assertEquals(1, surface.attaches)
    }

    @Test fun `no surface means the placeholder with no attach`() {
        val fallback = FakeFrameSource()
        assertSame(
            fallback,
            FrameSources.choose(null, FakeContext(), VideoSpec(800, 480, 30, 160, false), fallback),
        )
    }

    @Test fun `registry holds one installed surface and can be cleared`() {
        val surface = FakeSurface(emptySet())
        HeadunitSurfaces.install(surface)
        assertSame(surface, HeadunitSurfaces.current)
        HeadunitSurfaces.clear()
        assertNull(HeadunitSurfaces.current)
    }

    @Test fun `declared input reaches the attached surface and detach ends it`() {
        val surface = FakeSurface(setOf(SurfaceCapability.TOUCH, SurfaceCapability.KEYS))
        val runner = GearslipRunner(
            input = ByteArrayInputStream(byteArrayOf()),
            output = ByteArrayOutputStream(),
            identityProvider = ::identity,
            surface = surface,
        )
        val pointer = Protobuf.bytesField(
            1, Protobuf.varintField(1, 100) + Protobuf.varintField(2, 200) + Protobuf.varintField(3, 0),
        )
        val touchReport = pointer + Protobuf.varintField(3, 0)
        val key = Protobuf.varintField(1, 21) + Protobuf.varintField(2, 1) // DPAD_UP down
        val knob = Protobuf.varintField(1, 65536) + Protobuf.varintField(2, (-1).toLong())

        try {
            invoke(runner, "onInputReport", Protobuf.varintField(1, 1000) + Protobuf.bytesField(3, touchReport))
            invoke(runner, "onKeyEvent", Protobuf.bytesField(1, key))
            invoke(runner, "onRelativeEvent", Protobuf.bytesField(1, knob))
            assertEquals(1, surface.touches.size)
            assertEquals(100f, surface.touches[0].third[0].x)
            assertEquals(1, surface.keys.size)
            assertEquals(21, surface.keys[0].first)
            assertEquals(1, surface.rotations.size)
            assertEquals(-1, surface.rotations[0])
        } finally {
            runner.stop()
        }
        assertEquals(1, surface.detaches)
    }

    @Test fun `input is dropped when the surface declares no input capabilities`() {
        val surface = FakeSurface(emptySet())
        val runner = GearslipRunner(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), ::identity, surface = surface)
        val pointer = Protobuf.bytesField(
            1, Protobuf.varintField(1, 100) + Protobuf.varintField(2, 200) + Protobuf.varintField(3, 0),
        )
        try {
            invoke(runner, "onInputReport", Protobuf.varintField(1, 1000) + Protobuf.bytesField(3, pointer + Protobuf.varintField(3, 0)))
            invoke(runner, "onKeyEvent", Protobuf.bytesField(1, Protobuf.varintField(1, 21) + Protobuf.varintField(2, 1)))
            assertTrue(surface.touches.isEmpty())
            assertTrue(surface.keys.isEmpty())
        } finally {
            runner.stop()
        }
    }

    private fun invoke(runner: GearslipRunner, name: String, body: ByteArray) {
        GearslipRunner::class.java.getDeclaredMethod(name, ByteArray::class.java)
            .apply { isAccessible = true }
            .invoke(runner, body)
    }

    private fun identity(): CertProvider.Identity {
        val generated = SelfSignedCert.generate()
        return CertProvider.Identity(
            generated.keyStore, generated.certificate, SelfSignedCert.PASSWORD, "generated test identity",
        )
    }
}
