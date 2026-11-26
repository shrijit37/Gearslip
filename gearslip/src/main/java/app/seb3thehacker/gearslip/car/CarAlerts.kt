package app.seb3thehacker.gearslip.car

import android.os.Handler
import android.os.Looper
import androidx.annotation.MainThread
import androidx.car.app.model.Action
import androidx.car.app.model.Alert
import androidx.car.app.model.AlertCallback
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.host.cancel
import app.seb3thehacker.gearslip.host.click
import app.seb3thehacker.gearslip.host.dismissed
import app.seb3thehacker.gearslip.host.text
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The alert a hosted car app asked to show, held as session state. Rendering is up to whichever
 * head-unit surface is attached; the protocol side only owns the queue and the callbacks.
 */
@MainThread
internal object CarAlerts {
    class Shown(val owner: Any, val alert: Alert)

    private val current = MutableStateFlow<Shown?>(null)
    val shown: StateFlow<Shown?> = current.asStateFlow()

    private val handler = Handler(Looper.getMainLooper())
    private var timeout: Runnable? = null

    private fun cancelTimeout() {
        timeout?.let(handler::removeCallbacks)
        timeout = null
    }

    fun show(owner: Any, alert: Alert) {
        val old = current.value
        cancelTimeout()
        val shown = Shown(owner, alert)
        current.value = shown
        GearslipLog.i("host: alert ${alert.id} \"${alert.title.text()}\" with ${alert.actions.size} buttons")
        if (old != null && !(old.owner === owner && old.alert.id == alert.id)) old.alert.callbackDelegate.dismissed()
        // The deleted car UI overlay used to own this timer. Without a surface there is no
        // composable to run it, so the session has to tell the app its alert timed out.
        val duration = alert.durationMillis
        if (duration > 0 && duration != Alert.DURATION_SHOW_INDEFINITELY.toLong()) {
            val r = Runnable { cancelled(shown, AlertCallback.REASON_TIMEOUT) }
            timeout = r
            handler.postDelayed(r, duration)
        }
    }

    /** The app took its alert down; a no-op unless that alert is the one showing. */
    fun dismiss(owner: Any, id: Int) {
        val shown = current.value ?: return
        if (shown.owner !== owner || shown.alert.id != id) return
        cancelTimeout()
        current.value = null
        shown.alert.callbackDelegate.dismissed()
    }

    /** The driver tapped one of its buttons: the app gets the tap, and the alert goes. */
    fun tapped(shown: Shown, action: Action) {
        if (current.value !== shown) return
        action.onClickDelegate.click("alert button")
        cancelTimeout()
        current.value = null
        shown.alert.callbackDelegate.dismissed()
    }

    fun cancelled(shown: Shown, reason: Int) {
        if (current.value !== shown) return
        cancelTimeout()
        current.value = null
        shown.alert.callbackDelegate.cancel(reason)
    }

    /** The app disconnected: its alert goes with it, and there is no one left to tell. */
    fun clear(owner: Any) {
        if (current.value?.owner === owner) {
            cancelTimeout()
            current.value = null
        }
    }
}
