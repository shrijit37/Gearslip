package app.seb3thehacker.gearslip.surface

import app.seb3thehacker.gearslip.Insets
import app.seb3thehacker.gearslip.SessionStatus
import app.seb3thehacker.gearslip.car.CarData
import app.seb3thehacker.gearslip.car.CarEnvironment
import app.seb3thehacker.gearslip.protocol.TouchPoint
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals

/**
 * Plug-in test doubles shared across the surface suite.
 *
 * Extracted in Phase 0 so the contract tests added in later phases (throwing plug-ins, swaps,
 * focus routing) can reuse the same guaranteed-simple implementations instead of growing private
 * copies in each test file.
 */

class FakeFrameSource : FrameSource {
    override var bitRate = 1_000
    override var fullBitRate = 2_000
    var starts = 0
    var output: FrameOutput? = null

    override fun start(output: FrameOutput) { starts++; this.output = output }
    override fun requestSyncFrame() = Unit
    override fun applyBitRate(bps: Int) { bitRate = bps }
    override fun stop() = Unit
}

class FakeSurface(
    override val capabilities: Set<SurfaceCapability>,
    private val provided: FrameSource? = null,
) : HeadunitSurface {
    override val id = "fake-surface"
    var attaches = 0
        private set
    var detaches = 0
        private set
    var focus: Boolean? = null
        private set
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

    /** When true, a trusted runner keeps the key from the core handler. */
    var consumeKeys = false

    override fun onKey(keyCode: Int, down: Boolean, longPress: Boolean): Boolean {
        keys += Triple(keyCode, down, longPress)
        return consumeKeys
    }

    override fun onRotate(delta: Int) { rotations += delta }
}

class FakeContext : SurfaceContext {
    override val session = MutableStateFlow(SessionStatus.state.value)
    override val frame = MutableStateFlow(CarEnvironment.Frame(800, 480, 160))
    override val insets = MutableStateFlow(Insets.NONE)
    override val night = MutableStateFlow(false)
    override val carData = MutableStateFlow(CarData.Snapshot())
    override val vehicle = MutableStateFlow("test car")
}
