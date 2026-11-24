package app.seb3thehacker.gearslip.car

import app.seb3thehacker.gearslip.AppSettings
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/** The music layout switch is shared by the car and phone settings, so it must persist and fall back. */
class MusicUiSettingTest {
    private val context = InMemoryContext()

    @After fun reset() {
        CarSettings.setMusicUi(CarSettings.MusicUi.CLASSIC)
    }

    @Test fun `the chosen layout is read back on the next init`() {
        CarSettings.init(context)
        assertEquals(CarSettings.MusicUi.CLASSIC, CarSettings.musicUi.value)

        CarSettings.setMusicUi(CarSettings.MusicUi.STITCH)
        CarSettings.init(context)
        assertEquals(CarSettings.MusicUi.STITCH, CarSettings.musicUi.value)
    }

    @Test fun `an unknown saved layout falls back to classic`() {
        CarSettings.init(context)
        AppSettings.putString(context, "car_music_ui", "RETIRED_LAYOUT")
        CarSettings.init(context)
        assertEquals(CarSettings.MusicUi.CLASSIC, CarSettings.musicUi.value)
    }
}
