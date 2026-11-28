package app.seb3thehacker.gearslip.surface

import app.seb3thehacker.gearslip.surface.api.KeyCodes
import org.junit.Assert.assertEquals
import org.junit.Test

/** The plain codes must keep the numbers the head unit sends over the wire. */
class KeyCodesTest {

    @Test fun `codes match the Android values the car sends`() {
        assertEquals(5, KeyCodes.CALL)
        assertEquals(6, KeyCodes.ENDCALL)
        assertEquals(3, KeyCodes.HOME)
        assertEquals(4, KeyCodes.BACK)
        assertEquals(19, KeyCodes.DPAD_UP)
        assertEquals(20, KeyCodes.DPAD_DOWN)
        assertEquals(21, KeyCodes.DPAD_LEFT)
        assertEquals(22, KeyCodes.DPAD_RIGHT)
        assertEquals(23, KeyCodes.DPAD_CENTER)
        assertEquals(66, KeyCodes.ENTER)
        assertEquals(85, KeyCodes.MEDIA_PLAY_PAUSE)
        assertEquals(86, KeyCodes.MEDIA_STOP)
        assertEquals(87, KeyCodes.MEDIA_NEXT)
        assertEquals(88, KeyCodes.MEDIA_PREVIOUS)
        assertEquals(126, KeyCodes.MEDIA_PLAY)
        assertEquals(127, KeyCodes.MEDIA_PAUSE)
        assertEquals(0, KeyCodes.ACTION_DOWN)
        assertEquals(1, KeyCodes.ACTION_UP)
    }
}
