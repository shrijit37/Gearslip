package app.seb3thehacker.gearslip.surface.api

/**
 * Plain key codes, independent of `android.view.KeyEvent`.
 *
 * The head unit sends the same numbers over the wire, but core key policy and the plug-in contract
 * no longer need an Android class to name them. A renderer can therefore map its own key codes
 * without importing the Android SDK. Values match Android's constants for compatibility.
 */
object KeyCodes {
    const val CALL = 5
    const val ENDCALL = 6
    const val HOME = 3
    const val BACK = 4

    const val DPAD_UP = 19
    const val DPAD_DOWN = 20
    const val DPAD_LEFT = 21
    const val DPAD_RIGHT = 22
    const val DPAD_CENTER = 23
    const val ENTER = 66

    const val MEDIA_PLAY_PAUSE = 85
    const val MEDIA_STOP = 86
    const val MEDIA_NEXT = 87
    const val MEDIA_PREVIOUS = 88
    const val MEDIA_PLAY = 126
    const val MEDIA_PAUSE = 127

    const val ACTION_DOWN = 0
    const val ACTION_UP = 1
}
