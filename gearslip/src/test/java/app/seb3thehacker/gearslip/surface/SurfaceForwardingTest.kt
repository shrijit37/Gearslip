package app.seb3thehacker.gearslip.surface

import app.seb3thehacker.gearslip.CertProvider
import app.seb3thehacker.gearslip.car.CarKeys
import app.seb3thehacker.gearslip.GearslipRunner
import app.seb3thehacker.gearslip.SelfSignedCert
import app.seb3thehacker.gearslip.Protobuf
import kotlinx.coroutines.yield
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Focus and frame-source forwarding at the plug-in seam (finding 10). */
class SurfaceForwardingTest {

    @Test fun `video focus reaches the attached surface`() {
        val surface = FakeSurface(setOf(SurfaceCapability.TOUCH))
        val runner = GearslipRunner(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), ::identity, surface = surface)
        try {
            invoke(runner, "onVideoFocus", 1) // VIDEO_FOCUS_PROJECTED
            assertEquals(true, surface.focus)
        } finally {
            runner.stop()
        }
    }

    @Test fun `a stopped session still detaches exactly once`() {
        val surface = FakeSurface(emptySet())
        val runner = GearslipRunner(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), ::identity, surface = surface)
        runner.stop()
        runner.stop()
        assertEquals(1, surface.detaches)
    }

    @Test fun `bitrate decisions reach a plug-in frame source`() {
        val source = FakeFrameSource()
        val runner = GearslipRunner(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), ::identity)
        try {
            GearslipRunner::class.java.getDeclaredField("frameSource").apply {
                isAccessible = true
                set(runner, source)
            }
            GearslipRunner::class.java.getDeclaredField("adaptiveVideo").apply {
                isAccessible = true
                setBoolean(runner, true)
            }
            val adapt = GearslipRunner::class.java.getDeclaredMethod(
                "adaptBitRate", Double::class.javaPrimitiveType, Long::class.javaPrimitiveType,
            ).apply { isAccessible = true }
            // Two easy windows are needed before the climb is applied.
            adapt.invoke(runner, 60.0, 0L)
            adapt.invoke(runner, 60.0, 0L)
            assertEquals(1_101, source.bitRate)
        } finally {
            runner.stop()
        }
    }

    @Test fun `a throwing detach does not stop the runner`() {
        var attempts = 0
        val surface = object : HeadunitSurface {
            override val id = "throwing-detach"
            override val capabilities = emptySet<SurfaceCapability>()
            override fun detach() {
                attempts++
                throw IllegalStateException("detach")
            }
        }
        val runner = GearslipRunner(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), ::identity, surface = surface)
        runner.stop() // guarded: must not propagate
        assertEquals(1, attempts) // the call really happened, and was contained
    }

    @Test fun `a consuming surface keeps a key from the core handler`() = runBlocking {
        val surface = FakeSurface(setOf(SurfaceCapability.KEYS)).apply { consumeKeys = true }
        val runner = GearslipRunner(
            ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), ::identity,
            surface = surface, selectionTrusted = true,
        )
        val seen = mutableListOf<CarKeys.ScreenKey>()
        val collector = launch { CarKeys.screenKeys.collect { seen += it } }
        try {
            yield() // let the collector subscribe before the key is dispatched
            invoke(runner, "onKeyEvent", keyEvent())
            delay(50)
            assertEquals(1, surface.keys.size)
            assertEquals(0, seen.size)
        } finally {
            collector.cancel()
            runner.stop()
        }
    }

    @Test fun `the same key reaches the core when the surface does not consume it`() = runBlocking {
        val surface = FakeSurface(setOf(SurfaceCapability.KEYS)) // consumeKeys defaults to false
        val runner = GearslipRunner(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), ::identity, surface = surface)
        val seen = mutableListOf<CarKeys.ScreenKey>()
        val collector = launch { CarKeys.screenKeys.collect { seen += it } }
        try {
            yield()
            invoke(runner, "onKeyEvent", keyEvent())
            delay(50)
            assertEquals(1, surface.keys.size)
            assertTrue(CarKeys.ScreenKey.BACK in seen)
        } finally {
            collector.cancel()
            runner.stop()
        }
    }

    @Test fun `an untrusted surface cannot suppress core key handling`() = runBlocking {
        val surface = FakeSurface(setOf(SurfaceCapability.KEYS)).apply { consumeKeys = true }
        val runner = GearslipRunner(
            ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), ::identity,
            surface = surface, selectionTrusted = false,
        )
        val seen = mutableListOf<CarKeys.ScreenKey>()
        val collector = launch { CarKeys.screenKeys.collect { seen += it } }
        try {
            yield()
            invoke(runner, "onKeyEvent", keyEvent())
            delay(50)
            assertEquals(1, surface.keys.size)
            assertTrue(CarKeys.ScreenKey.BACK in seen)
        } finally {
            collector.cancel()
            runner.stop()
        }
    }

    @Test fun `regaining projected focus asks the frame source to resync`() {
        val runner = GearslipRunner(ByteArrayInputStream(byteArrayOf()), ByteArrayOutputStream(), ::identity)
        val source = FakeFrameSource()
        val cls = GearslipRunner::class.java
        try {
            cls.getDeclaredField("frameSource").apply { isAccessible = true; set(runner, source) }
            cls.getDeclaredField("videoStarted").apply { isAccessible = true; setBoolean(runner, true) }
            cls.getDeclaredField("videoProjected").apply { isAccessible = true; setBoolean(runner, false) }
            cls.getDeclaredMethod("onVideoFocus", Int::class.javaPrimitiveType)
                .apply { isAccessible = true }
                .invoke(runner, 1) // VIDEO_FOCUS_PROJECTED
            assertEquals(1, source.syncFrames)
        } finally {
            runner.stop()
        }
    }

    private fun keyEvent(): ByteArray {
        val key = Protobuf.varintField(1, 4) + Protobuf.varintField(2, 1) // BACK down
        return Protobuf.bytesField(1, key)
    }

    private fun invoke(runner: GearslipRunner, name: String, body: ByteArray) {
        GearslipRunner::class.java.getDeclaredMethod(name, ByteArray::class.java)
            .apply { isAccessible = true }
            .invoke(runner, body)
    }

    private fun invoke(runner: GearslipRunner, name: String, arg: Int) {
        GearslipRunner::class.java.getDeclaredMethod(name, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }
            .invoke(runner, arg)
    }

    private fun identity(): CertProvider.Identity {
        val generated = SelfSignedCert.generate()
        return CertProvider.Identity(
            generated.keyStore, generated.certificate, SelfSignedCert.PASSWORD, "generated test identity",
        )
    }
}
