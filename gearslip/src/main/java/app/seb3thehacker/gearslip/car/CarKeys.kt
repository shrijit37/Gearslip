package app.seb3thehacker.gearslip.car

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.call.CallUiState
import app.seb3thehacker.gearslip.call.CarCalls
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The car's own buttons: steering wheel, rotary knob, D-pad and the hard keys around the screen.
 *
 * The head unit sends Android keycodes as they are, so the numbers here are android.view.KeyEvent's.
 * Media and call keys act straight away; screen keys are published for whichever head-unit UI is
 * attached, so handling a key never depends on a particular UI being present.
 */
object CarKeys {

    /** A key that changes which screen is showing. */
    enum class ScreenKey { BACK, HOME, MAP, MEDIA, PHONE }

    private val _screenKeys = MutableSharedFlow<ScreenKey>(extraBufferCapacity = 8)
    val screenKeys: SharedFlow<ScreenKey> = _screenKeys

    private var appContext: Context? = null
    private val main = Handler(Looper.getMainLooper())

    fun init(context: Context) { appContext = context.applicationContext }

    /** The keys that act on the media app. */
    private val MEDIA_KEYS = setOf(
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_STOP, KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS,
    )

    /**
     * Every key worth asking the head unit for. The binding request lists what the head unit
     * offered; when it offers nothing (some units leave the list empty and send keys anyway),
     * this is what's asked for instead.
     */
    val WANTED = listOf(
        KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
        KeyEvent.KEYCODE_MEDIA_NEXT, KeyEvent.KEYCODE_MEDIA_PREVIOUS, KeyEvent.KEYCODE_MEDIA_STOP,
        KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_ENDCALL,
        KeyEvent.KEYCODE_BACK, KeyEvent.KEYCODE_HOME,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
        KEYCODE_ROTARY_CONTROLLER, KEYCODE_MEDIA, KEYCODE_NAVIGATION, KEYCODE_TEL,
    )

    /**
     * One key edge from the head unit. Most keys act on the press; focus keys pass both edges on,
     * so a held D-pad key repeats the way it would anywhere else. Returns false for a key nothing
     * here knows, for the caller to log.
     */
    fun onKey(keyCode: Int, down: Boolean, longPress: Boolean): Boolean {
        // Focus keys belong to the attached UI, if any; the core consumes them either way.
        if (keyCode in FOCUS_KEYS) return true
        if (!down) return keyCode in WANTED
        // Null until the car shows Gearslip's screen. A 2020 Civic sends Play as soon as it hears
        // the phone's Bluetooth address, while still on its own screen, and reaching for media
        // then ended the session (issue #43).
        val media = CarServices.mediaOrNull()
        if (media == null && keyCode in MEDIA_KEYS) {
            GearslipLog.i("keys: $keyCode before the car shows Gearslip - no media app to send it to yet")
            return true
        }
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> media?.togglePlay()
            KeyEvent.KEYCODE_MEDIA_PLAY -> if (media != null && !media.now.value.playing) media.togglePlay()
            KeyEvent.KEYCODE_MEDIA_PAUSE, KeyEvent.KEYCODE_MEDIA_STOP -> if (media != null && media.now.value.playing) media.togglePlay()
            KeyEvent.KEYCODE_MEDIA_NEXT -> media?.next()
            KeyEvent.KEYCODE_MEDIA_PREVIOUS -> media?.previous()
            KeyEvent.KEYCODE_CALL -> call()
            KeyEvent.KEYCODE_ENDCALL -> endCall()
            KeyEvent.KEYCODE_BACK -> screen(ScreenKey.BACK)
            KeyEvent.KEYCODE_HOME -> screen(ScreenKey.HOME)
            KEYCODE_NAVIGATION -> screen(ScreenKey.MAP)
            KEYCODE_MEDIA -> screen(ScreenKey.MEDIA)
            KEYCODE_TEL -> screen(ScreenKey.PHONE)
            else -> return false
        }
        if (longPress) GearslipLog.i("keys: long press on $keyCode")
        return true
    }

    /** Answers a ringing call; with nothing ringing, opens the phone screen. */
    private fun call() = main.post {
        val context = appContext ?: return@post
        if (CarCalls.state.value is CallUiState.Ringing) CarCalls.answer(context) else screen(ScreenKey.PHONE)
    }

    private fun endCall() = main.post {
        if (CarCalls.state.value is CallUiState.Ringing) CarCalls.decline()
        else GearslipLog.i("keys: end call pressed with no ringing call; hanging up needs the dialer")
    }

    private fun screen(key: ScreenKey) { _screenKeys.tryEmit(key) }

    // aap_protobuf's KeyCode.proto: car-only keys past the end of Android's own range.
    const val KEYCODE_ROTARY_CONTROLLER = 65536
    const val KEYCODE_MEDIA = 65537
    const val KEYCODE_NAVIGATION = 65538
    const val KEYCODE_TEL = 65540

    private val FOCUS_KEYS = setOf(
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
    )
}
